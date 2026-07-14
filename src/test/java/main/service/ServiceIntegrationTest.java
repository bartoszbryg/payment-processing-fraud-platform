package main.service;

import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.databaseModel.User;
import main.dto.request.PaymentRequest;
import main.dto.request.RegisterRequest;
import main.dto.response.*;
import main.exception.InsufficientBalanceException;
import main.exception.ResourceNotFoundException;
import main.queue.TransactionQueue;
import main.queue.TransactionWorkerPool;
import main.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/*
    Full Spring context integration test — every service uses real beans wired together.

    H2 is used via the "dev" profile (same config as RepositoryIntegrationTest).
    @Transactional on the class rolls back each test, keeping tests isolated.
    @MockBean RestTemplate prevents real HTTP calls to the ML microservice.
    CacheManager is injected to clear the Caffeine "dashboardStats" cache between tests,
    since @Transactional rollback clears the DB but not the in-memory cache.
*/
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServiceIntegrationTest {

    // external dependency stubs
    @MockBean RestTemplate restTemplate; // prevents real HTTP to ML scoring service

    // services under test
    @Autowired AuthService authService;
    @Autowired UserService userService;
    @Autowired PaymentService paymentService;
    @Autowired FraudAlertService fraudAlertService;
    @Autowired DashboardService dashboardService;

    // infrastructure
    @Autowired UserRepository userRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired FraudAlertRepository fraudAlertRepository;
    @Autowired TransactionQueue transactionQueue;
    @Autowired TransactionWorkerPool workerPool;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void evictDashboardCache() {
        // @Transactional rolls back DB changes after each test but not the Caffeine cache.
        // DashboardService.getStats() is @Cacheable with a fixed key, so without eviction
        // the first test's cached result would bleed into subsequent tests.
        var cache = cacheManager.getCache("dashboardStats");
        if (cache != null) cache.clear();
    }

    // shared test data helpers

    private RegisterRequest registerRequest(String name, String email, String username) {
        RegisterRequest req = new RegisterRequest();
        req.setName(name);
        req.setEmail(email);
        req.setUsername(username);
        req.setPassword("S3cur3P@ss!");
        req.setPhoneNumber("+14155552671");
        req.setInitialBalance(new BigDecimal("5000.00"));
        req.setHomeCountry("US");
        req.setHomeCity("New York");
        return req;
    }

    private PaymentRequest merchantRequest(BigDecimal amount) {
        PaymentRequest req = new PaymentRequest();
        req.setAmount(amount);
        req.setMerchant("Amazon");
        req.setLocation("New York");
        req.setCountry("US");
        return req;
    }

    private PaymentRequest p2pRequest(String receiverId, BigDecimal amount) {
        PaymentRequest req = new PaymentRequest();
        req.setAmount(amount);
        req.setReceiverId(receiverId);
        req.setLocation("New York");
        req.setCountry("US");
        return req;
    }

    private FraudAlert buildAlert(Transaction txn, String userId, FraudRuleType ruleType) {
        return FraudAlert.builder()
            .transaction(txn)
            .userId(userId)
            .ruleType(ruleType)
            .riskLevel(RiskLevel.HIGH)
            .scoreContribution(40.0)
            .description("Test alert")
            .build();
    }

    // AuthService

    @Nested
    class AuthServiceIntegrationTests {

        @Test
        void register_createsUserAndAppUser() {
            UserResponse response = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));

            assertNotNull(response.getId());
            assertEquals("Alice Smith", response.getName());
            assertEquals("alice@example.com", response.getEmail());
            assertEquals(new BigDecimal("5000.00"), response.getBalance());
            assertTrue(response.isActive());

            // Both User and AppUser must be persisted
            assertTrue(userRepository.existsByEmail("alice@example.com"));
            assertTrue(appUserRepository.existsByUsername("alice"));
        }

        @Test
        void register_appUserLinkedToUser() {
            UserResponse response = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));

            var appUser = appUserRepository.findByUsername("alice").orElseThrow();
            assertEquals(response.getId(), appUser.getLinkedUserId());
            assertEquals("alice@example.com", appUser.getEmail());
            assertTrue(appUser.getRoles().contains("ROLE_CUSTOMER"));
        }

        @Test
        void register_passwordStoredHashed() {
            authService.register(registerRequest("Alice Smith", "alice@example.com", "alice"));

            var appUser = appUserRepository.findByUsername("alice").orElseThrow();
            // Raw password must never be stored
            assertNotEquals("S3cur3P@ss!", appUser.getPassword());
            assertTrue(appUser.getPassword().startsWith("$2")); // BCrypt prefix
        }

        @Test
        void register_duplicateEmail_throwsAndSavesNothing() {
            authService.register(registerRequest("Alice Smith", "alice@example.com", "alice"));

            assertThrows(IllegalArgumentException.class, () ->
                authService.register(registerRequest("Alice Duplicate", "alice@example.com", "alice2")));

            // alice2 AppUser must not have been saved
            assertFalse(appUserRepository.existsByUsername("alice2"));
        }

        @Test
        void register_duplicateUsername_throwsAndSavesNothing() {
            authService.register(registerRequest("Alice Smith", "alice@example.com", "alice"));

            assertThrows(IllegalArgumentException.class, () ->
                authService.register(registerRequest("Alice Clone", "alice2@example.com", "alice")));

            // alice2@example.com User must not have been saved
            assertFalse(userRepository.existsByEmail("alice2@example.com"));
        }
    }

    // UserService

    @Nested
    class UserServiceIntegrationTests {

        @Test
        void getUser_returnsRegisteredUser() {
            UserResponse registered = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));

            UserResponse fetched = userService.getUser(registered.getId());

            assertEquals(registered.getId(), fetched.getId());
            assertEquals("Alice Smith", fetched.getName());
            assertEquals("alice@example.com", fetched.getEmail());
            assertTrue(fetched.isActive());
        }

        @Test
        void getUser_notFound_throws() {
            assertThrows(ResourceNotFoundException.class,
                () -> userService.getUser("non-existent-id"));
        }

        @Test
        void getAllUsers_includesRegisteredUser() {
            authService.register(registerRequest("Alice Smith", "alice@example.com", "alice"));
            authService.register(registerRequest("Bob Jones", "bob@example.com", "bob"));

            Page<UserResponse> page = userService.getAllUsers(PageRequest.of(0, 10));

            assertTrue(page.getTotalElements() >= 2);
        }

        @Test
        void deactivateUser_setsActiveFalse() {
            UserResponse registered = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));

            UserResponse deactivated = userService.deactivateUser(registered.getId());

            assertFalse(deactivated.isActive());

            // Verify persisted in DB
            User fromDb = userRepository.findById(registered.getId()).orElseThrow();
            assertFalse(fromDb.isActive());
        }

        @Test
        void getHighRiskUsers_returnsUsersAboveThreshold() {
            UserResponse registered = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            // Directly update risk score in DB
            userRepository.updateRiskProfile(registered.getId(), 80.0, true);

            Page<UserResponse> highRisk = userService.getHighRiskUsers(70.0, PageRequest.of(0, 10));

            assertTrue(highRisk.getTotalElements() >= 1);
            assertTrue(highRisk.getContent().stream()
                .anyMatch(u -> u.getId().equals(registered.getId())));
        }

        @Test
        void getFlaggedUsers_returnsOnlyFlaggedActive() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            authService.register(registerRequest("Bob Jones", "bob@example.com", "bob"));

            userRepository.updateRiskProfile(alice.getId(), 85.0, true); // flags alice

            Page<UserResponse> flagged = userService.getFlaggedUsers(PageRequest.of(0, 10));

            assertTrue(flagged.getTotalElements() >= 1);
            assertTrue(flagged.getContent().stream()
                .anyMatch(u -> u.getId().equals(alice.getId())));
        }
    }

    // PaymentService

    @Nested
    class PaymentServiceIntegrationTests {

        @Test
        void submitMerchantPayment_persistsAndEnqueues() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));

            PaymentResponse response = paymentService.submitPayment(
                alice.getId(), merchantRequest(new BigDecimal("100.00")));

            assertEquals(TransactionStatus.PENDING, response.getStatus());
            assertNotNull(response.getTransactionId());
            assertEquals(0.0, response.getFraudScore(), 0.001);

            // Transaction persisted in DB
            assertTrue(transactionRepository.findById(response.getTransactionId()).isPresent());
        }

        @Test
        void submitP2pPayment_linksReceiverInTransaction() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            UserResponse bob = authService.register(
                registerRequest("Bob Jones", "bob@example.com", "bob"));

            PaymentResponse response = paymentService.submitPayment(
                alice.getId(), p2pRequest(bob.getId(), new BigDecimal("200.00")));

            Transaction txn = transactionRepository.findById(response.getTransactionId()).orElseThrow();
            assertEquals(alice.getId(), txn.getSender().getId());
            assertEquals(bob.getId(), txn.getReceiver().getId());
            assertTrue(txn.isP2P());
        }

        @Test
        void submitPayment_inactiveUser_throws() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            userService.deactivateUser(alice.getId());

            assertThrows(IllegalStateException.class, () ->
                paymentService.submitPayment(alice.getId(), merchantRequest(new BigDecimal("100.00"))));
        }

        @Test
        void submitPayment_insufficientBalance_throws() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice")); // balance = 5000

            assertThrows(InsufficientBalanceException.class, () ->
                paymentService.submitPayment(alice.getId(), merchantRequest(new BigDecimal("9999.00"))));
        }

        @Test
        void submitPayment_userNotFound_throws() {
            assertThrows(ResourceNotFoundException.class, () ->
                paymentService.submitPayment("ghost-id", merchantRequest(new BigDecimal("10.00"))));
        }

        @Test
        void submitPayment_bothMerchantAndReceiverId_throws() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            UserResponse bob = authService.register(
                registerRequest("Bob Jones", "bob@example.com", "bob"));

            PaymentRequest bad = merchantRequest(new BigDecimal("50.00"));
            bad.setReceiverId(bob.getId()); // sets both merchant and receiverId

            assertThrows(IllegalArgumentException.class, () ->
                paymentService.submitPayment(alice.getId(), bad));
        }

        @Test
        void getTransaction_returnsCorrectResponse() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            PaymentResponse payment = paymentService.submitPayment(
                alice.getId(), merchantRequest(new BigDecimal("50.00")));

            TransactionResponse txn = paymentService.getTransaction(payment.getTransactionId());

            assertEquals(payment.getTransactionId(), txn.getId());
            assertEquals(alice.getId(), txn.getUserId());
            assertEquals(TransactionStatus.PENDING, txn.getStatus());
            assertEquals("Amazon", txn.getMerchant());
        }

        @Test
        void getUserTransactions_returnsOnlySendersTransactions() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            UserResponse bob = authService.register(
                registerRequest("Bob Jones", "bob@example.com", "bob"));

            paymentService.submitPayment(alice.getId(), merchantRequest(new BigDecimal("100.00")));
            paymentService.submitPayment(alice.getId(), merchantRequest(new BigDecimal("200.00")));
            paymentService.submitPayment(bob.getId(), merchantRequest(new BigDecimal("50.00")));

            Page<TransactionResponse> aliceTxns = paymentService.getUserTransactions(
                alice.getId(), PageRequest.of(0, 10));

            assertEquals(2, aliceTxns.getTotalElements());
            assertTrue(aliceTxns.getContent().stream()
                .allMatch(t -> alice.getId().equals(t.getUserId())));
        }

        @Test
        void getAllTransactions_includesAllSubmitted() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));

            paymentService.submitPayment(alice.getId(), merchantRequest(new BigDecimal("50.00")));
            paymentService.submitPayment(alice.getId(), merchantRequest(new BigDecimal("75.00")));

            Page<TransactionResponse> all = paymentService.getAllTransactions(PageRequest.of(0, 10));

            assertTrue(all.getTotalElements() >= 2);
        }

        @Test
        void getFlaggedTransactions_returnsOnlyFlaggedStatuses() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));

            // Save a flagged transaction directly via repository
            Transaction flagged = transactionRepository.save(Transaction.builder()
                .sender(userRepository.findById(alice.getId()).orElseThrow())
                .merchant("ShadyStore")
                .amount(new BigDecimal("999.00"))
                .location("New York").country("US")
                .status(TransactionStatus.FLAGGED_FOR_REVIEW)
                .fraudScore(72.0)
                .build());

            // Normal pending transaction — must not appear in flagged list
            transactionRepository.save(Transaction.builder()
                .sender(userRepository.findById(alice.getId()).orElseThrow())
                .merchant("Amazon")
                .amount(new BigDecimal("50.00"))
                .location("New York").country("US")
                .status(TransactionStatus.PENDING)
                .build());

            Page<TransactionResponse> flaggedPage = paymentService.getFlaggedTransactions(PageRequest.of(0, 10));

            assertEquals(1, flaggedPage.getTotalElements());
            assertEquals(flagged.getId(), flaggedPage.getContent().get(0).getId());
        }
    }

    // FraudAlertService

    @Nested
    class FraudAlertServiceIntegrationTests {

        @Test
        void getAlertsForTransaction_returnsPersistedAlerts() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            PaymentResponse payment = paymentService.submitPayment(
                alice.getId(), merchantRequest(new BigDecimal("100.00")));
            Transaction txn = transactionRepository.findById(payment.getTransactionId()).orElseThrow();

            fraudAlertRepository.save(buildAlert(txn, alice.getId(), FraudRuleType.HIGH_AMOUNT));
            fraudAlertRepository.save(buildAlert(txn, alice.getId(), FraudRuleType.VELOCITY_BREACH));

            var alerts = fraudAlertService.getAlertsForTransaction(txn.getId());

            assertEquals(2, alerts.size());
            assertTrue(alerts.stream().allMatch(a -> a.getTransactionId().equals(txn.getId())));
        }

        @Test
        void getAlertsForUser_returnsPaged() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            UserResponse bob = authService.register(
                registerRequest("Bob Jones", "bob@example.com", "bob"));
            PaymentResponse alicePayment = paymentService.submitPayment(
                alice.getId(), merchantRequest(new BigDecimal("100.00")));
            PaymentResponse bobPayment = paymentService.submitPayment(
                bob.getId(), merchantRequest(new BigDecimal("50.00")));

            Transaction aliceTxn = transactionRepository.findById(alicePayment.getTransactionId()).orElseThrow();
            Transaction bobTxn = transactionRepository.findById(bobPayment.getTransactionId()).orElseThrow();

            fraudAlertRepository.save(buildAlert(aliceTxn, alice.getId(), FraudRuleType.HIGH_AMOUNT));
            fraudAlertRepository.save(buildAlert(aliceTxn, alice.getId(), FraudRuleType.VELOCITY_BREACH));
            fraudAlertRepository.save(buildAlert(bobTxn, bob.getId(), FraudRuleType.HIGH_AMOUNT));

            Page<FraudAlertResponse> aliceAlerts = fraudAlertService.getAlertsForUser(
                alice.getId(), PageRequest.of(0, 10));

            assertEquals(2, aliceAlerts.getTotalElements());
            assertTrue(aliceAlerts.getContent().stream()
                .allMatch(a -> alice.getId().equals(a.getUserId())));
        }

        @Test
        void getUnresolvedAlerts_excludesResolved() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            PaymentResponse payment = paymentService.submitPayment(
                alice.getId(), merchantRequest(new BigDecimal("100.00")));
            Transaction txn = transactionRepository.findById(payment.getTransactionId()).orElseThrow();

            FraudAlert unresolved = fraudAlertRepository.save(
                buildAlert(txn, alice.getId(), FraudRuleType.HIGH_AMOUNT));
            FraudAlert resolved = buildAlert(txn, alice.getId(), FraudRuleType.VELOCITY_BREACH);
            resolved.setResolved(true);
            fraudAlertRepository.save(resolved);

            Page<FraudAlertResponse> unresolvedPage = fraudAlertService.getUnresolvedAlerts(PageRequest.of(0, 10));

            // Only the unresolved alert must appear
            assertTrue(unresolvedPage.getContent().stream().noneMatch(FraudAlertResponse::isResolved));
            assertTrue(unresolvedPage.getContent().stream()
                .anyMatch(a -> a.getId().equals(unresolved.getId())));
        }

        @Test
        void resolveAlert_persistsResolvedTrue() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            PaymentResponse payment = paymentService.submitPayment(
                alice.getId(), merchantRequest(new BigDecimal("100.00")));
            Transaction txn = transactionRepository.findById(payment.getTransactionId()).orElseThrow();

            FraudAlert alert = fraudAlertRepository.save(
                buildAlert(txn, alice.getId(), FraudRuleType.HIGH_AMOUNT));

            FraudAlertResponse response = fraudAlertService.resolveAlert(alert.getId());

            assertTrue(response.isResolved());

            // Verify DB is updated
            FraudAlert fromDb = fraudAlertRepository.findById(alert.getId()).orElseThrow();
            assertTrue(fromDb.isResolved());
        }

        @Test
        void resolveAlert_notFound_throws() {
            assertThrows(ResourceNotFoundException.class,
                () -> fraudAlertService.resolveAlert("non-existent-alert-id"));
        }

        @Test
        void getAlertsByRuleSince_filtersCorrectly() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            PaymentResponse payment = paymentService.submitPayment(
                alice.getId(), merchantRequest(new BigDecimal("100.00")));
            Transaction txn = transactionRepository.findById(payment.getTransactionId()).orElseThrow();

            fraudAlertRepository.save(buildAlert(txn, alice.getId(), FraudRuleType.HIGH_AMOUNT));
            fraudAlertRepository.save(buildAlert(txn, alice.getId(), FraudRuleType.VELOCITY_BREACH));

            Instant oneHourAgo = Instant.now().minusSeconds(3600);
            var highAmountAlerts = fraudAlertService.getAlertsByRuleSince(
                FraudRuleType.HIGH_AMOUNT, oneHourAgo);

            assertEquals(1, highAmountAlerts.size());
            assertEquals(FraudRuleType.HIGH_AMOUNT, highAmountAlerts.get(0).getRuleType());
        }
    }

    // DashboardService

    @Nested
    class DashboardServiceIntegrationTests {

        @Test
        void getStats_totalTransactionsReflectsDB() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));

            paymentService.submitPayment(alice.getId(), merchantRequest(new BigDecimal("50.00")));
            paymentService.submitPayment(alice.getId(), merchantRequest(new BigDecimal("75.00")));

            DashboardStatsResponse stats = dashboardService.getStats();

            assertTrue(stats.getTotalTransactions() >= 2);
        }

        @Test
        void getStats_pendingIsNonNegative() {
            DashboardStatsResponse stats = dashboardService.getStats();

            assertTrue(stats.getTransactionsByStatus().get("PENDING") >= 0);
        }

        @Test
        void getStats_transactionsByStatusContainsAllKeys() {
            DashboardStatsResponse stats = dashboardService.getStats();

            assertTrue(stats.getTransactionsByStatus().containsKey("APPROVED"));
            assertTrue(stats.getTransactionsByStatus().containsKey("DECLINED"));
            assertTrue(stats.getTransactionsByStatus().containsKey("FRAUD_BLOCKED"));
            assertTrue(stats.getTransactionsByStatus().containsKey("FLAGGED_FOR_REVIEW"));
            assertTrue(stats.getTransactionsByStatus().containsKey("PENDING"));
        }

        @Test
        void getStats_queueDepthIsNonNegative() {
            DashboardStatsResponse stats = dashboardService.getStats();

            assertTrue(stats.getQueueDepth() >= 0);
        }

        @Test
        void getStats_activeWorkersIsNonNegative() {
            DashboardStatsResponse stats = dashboardService.getStats();

            assertTrue(stats.getActiveWorkers() >= 0);
        }

        @Test
        void getStats_alertsByRuleType_reflectsRecentAlerts() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            PaymentResponse payment = paymentService.submitPayment(
                alice.getId(), merchantRequest(new BigDecimal("100.00")));
            Transaction txn = transactionRepository.findById(payment.getTransactionId()).orElseThrow();

            fraudAlertRepository.save(buildAlert(txn, alice.getId(), FraudRuleType.HIGH_AMOUNT));
            fraudAlertRepository.flush(); // ensure data is visible to query

            // evict cache so this test sees fresh data
            var cache = cacheManager.getCache("dashboardStats");
            if (cache != null) cache.clear();

            DashboardStatsResponse stats = dashboardService.getStats();

            assertTrue(stats.getAlertsByRuleType().containsKey("HIGH_AMOUNT"));
            assertTrue(stats.getAlertsByRuleType().get("HIGH_AMOUNT") >= 1);
        }
    }

    // Cross-service scenarios

    @Nested
    class CrossServiceScenarioTests {

        @Test
        void fullFlow_registerSubmitGetResolve() {
            // 1. Register user
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));

            // 2. Submit payment
            PaymentResponse payment = paymentService.submitPayment(
                alice.getId(), merchantRequest(new BigDecimal("150.00")));

            // 3. Get transaction via PaymentService
            TransactionResponse txn = paymentService.getTransaction(payment.getTransactionId());
            assertEquals(TransactionStatus.PENDING, txn.getStatus());
            assertEquals("Amazon", txn.getMerchant());

            // 4. Create a fraud alert for that transaction
            Transaction txnEntity = transactionRepository.findById(payment.getTransactionId()).orElseThrow();
            FraudAlert alert = fraudAlertRepository.save(
                buildAlert(txnEntity, alice.getId(), FraudRuleType.HIGH_AMOUNT));

            // 5. Analyst views unresolved alerts
            Page<FraudAlertResponse> unresolved = fraudAlertService.getUnresolvedAlerts(PageRequest.of(0, 10));
            assertTrue(unresolved.getContent().stream().anyMatch(a -> a.getId().equals(alert.getId())));

            // 6. Analyst resolves the alert
            FraudAlertResponse resolved = fraudAlertService.resolveAlert(alert.getId());
            assertTrue(resolved.isResolved());

            // 7. Alert no longer appears in unresolved queue
            var cache = cacheManager.getCache("dashboardStats");
            if (cache != null) cache.clear(); // evict before re-querying
            Page<FraudAlertResponse> afterResolve = fraudAlertService.getUnresolvedAlerts(PageRequest.of(0, 10));
            assertTrue(afterResolve.getContent().stream().noneMatch(a -> a.getId().equals(alert.getId())));
        }

        @Test
        void deactivatedUser_cannotSubmitPayment() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));

            userService.deactivateUser(alice.getId());

            assertThrows(IllegalStateException.class, () ->
                paymentService.submitPayment(alice.getId(), merchantRequest(new BigDecimal("50.00"))));
        }

        @Test
        void p2pFlow_aliceSendsToBob_bothInTransaction() {
            UserResponse alice = authService.register(
                registerRequest("Alice Smith", "alice@example.com", "alice"));
            UserResponse bob = authService.register(
                registerRequest("Bob Jones", "bob@example.com", "bob"));

            PaymentResponse payment = paymentService.submitPayment(
                alice.getId(), p2pRequest(bob.getId(), new BigDecimal("300.00")));

            TransactionResponse txn = paymentService.getTransaction(payment.getTransactionId());
            assertEquals(alice.getId(), txn.getUserId());
            assertEquals(bob.getId(), txn.getReceiverId());
            assertNull(txn.getMerchant());
        }
    }
}