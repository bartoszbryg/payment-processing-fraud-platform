package main.repository;

import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;
import main.databaseModel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
public class RepositoryIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired FraudAlertRepository fraudAlertRepository;
    @Autowired MerchantBlacklistRepository merchantBlacklistRepository;
    @Autowired UserSessionRepository userSessionRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setup() {
        alice = userRepository.saveAndFlush(User.builder()
            .name("Alice Smith").email("alice@example.com")
            .phoneNumber("+14155552671")
            .balance(new BigDecimal("2000.00"))
            .homeCountry("US").homeCity("New York").build());

        bob = userRepository.saveAndFlush(User.builder()
            .name("Bob Jones").email("bob@example.com")
            .phoneNumber("+14155559999")
            .balance(new BigDecimal("500.00"))
            .homeCountry("US").build());
    }

    // UserRepository

    @Test
    void findByEmail() {
        Optional<User> found = userRepository.findByEmail("alice@example.com");
        assertTrue(found.isPresent());
        assertEquals("Alice Smith", found.get().getName());

        assertTrue(userRepository.findByEmail("ghost@example.com").isEmpty());
    }

    @Test
    void existsByEmail() {
        assertTrue(userRepository.existsByEmail("alice@example.com"));
        assertFalse(userRepository.existsByEmail("ghost@example.com"));
    }

    @Test
    void userDefaultValues() {
        assertTrue(alice.isActive());
        assertFalse(alice.isFlagged());
        assertEquals(0.0, alice.getRiskScore());
        assertFalse(alice.isEmailVerified());
        assertFalse(alice.isPhoneVerified());
        assertNotNull(alice.getCreatedAt());
    }

    @Test
    void duplicateEmailIsRejected() {
        assertThrows(Exception.class, () ->
            userRepository.saveAndFlush(User.builder()
                .name("Duplicate Alice").email("alice@example.com")
                .phoneNumber("+14155550001")
                .balance(new BigDecimal("100.00"))
                .homeCountry("US").build()));
    }

    @Test
    void findByFlagged() {
        alice.setFlagged(true);
        userRepository.save(alice);

        Page<User> flagged = userRepository.findByFlaggedAndActiveTrue(true, PageRequest.of(0, 10));
        assertEquals(1, flagged.getTotalElements());
        assertEquals("Alice Smith", flagged.getContent().get(0).getName());

        // deactivated flagged user must not appear on the analyst dashboard
        alice.setActive(false);
        userRepository.save(alice);
        assertEquals(0, userRepository.findByFlaggedAndActiveTrue(true, PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void findHighRiskUsers() {
        alice.setRiskScore(75.0);
        userRepository.save(alice);

        Page<User> highRisk = userRepository.findHighRiskUsers(50.0, PageRequest.of(0, 10));
        assertEquals(1, highRisk.getTotalElements());
        assertEquals(alice.getId(), highRisk.getContent().get(0).getId());

        assertEquals(0, userRepository.findHighRiskUsers(90.0, PageRequest.of(0, 10)).getTotalElements());

        // deactivated user must not appear in risk queue
        alice.setActive(false);
        userRepository.save(alice);
        assertEquals(0, userRepository.findHighRiskUsers(50.0, PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void deductBalance() {
        assertEquals(1, userRepository.deductBalance(alice.getId(), new BigDecimal("100.00")));
        assertEquals(0, userRepository.deductBalance(alice.getId(), new BigDecimal("99999.00")));

        User updated = userRepository.findById(alice.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("1900.00").compareTo(updated.getBalance()));
    }

    @Test
    void updateRiskProfile() {
        assertEquals(1, userRepository.updateRiskProfile(alice.getId(), 82.5, true));
        assertEquals(0, userRepository.updateRiskProfile("non-existent-id", 50.0, false));

        User result = userRepository.findById(alice.getId()).orElseThrow();
        assertEquals(82.5, result.getRiskScore());
        assertTrue(result.isFlagged());
    }

    @Test
    void updateRiskProfile_overwritesEvenWithLowerScore() {
        // Plain overwrite semantics: this is what raiseRiskProfile exists to NOT do.
        userRepository.updateRiskProfile(alice.getId(), 90.0, true);

        userRepository.updateRiskProfile(alice.getId(), 10.0, false);

        User result = userRepository.findById(alice.getId()).orElseThrow();
        assertEquals(10.0, result.getRiskScore());
        assertFalse(result.isFlagged());
    }

    @Test
    void raiseRiskProfile_higherScoreWins() {
        assertEquals(1, userRepository.raiseRiskProfile(alice.getId(), 90.0, true));
        assertEquals(0, userRepository.raiseRiskProfile("non-existent-id", 50.0, false));

        User result = userRepository.findById(alice.getId()).orElseThrow();
        assertEquals(90.0, result.getRiskScore());
        assertTrue(result.isFlagged());
    }

    @Test
    void raiseRiskProfile_neverLowersAnAlreadyHigherScore() {
        userRepository.updateRiskProfile(alice.getId(), 95.0, true);

        // A late, lower-severity signal (e.g. async ML enrichment) must not clobber it.
        userRepository.raiseRiskProfile(alice.getId(), 20.0, false);

        User result = userRepository.findById(alice.getId()).orElseThrow();
        assertEquals(95.0, result.getRiskScore());
        assertTrue(result.isFlagged());
    }

    @Test
    void raiseRiskProfile_stillRaisesALowerExistingScore() {
        userRepository.updateRiskProfile(alice.getId(), 15.0, false);

        userRepository.raiseRiskProfile(alice.getId(), 60.0, true);

        User result = userRepository.findById(alice.getId()).orElseThrow();
        assertEquals(60.0, result.getRiskScore());
        assertTrue(result.isFlagged());
    }

    @Test
    void markEmailVerified() {
        assertFalse(userRepository.findById(alice.getId()).orElseThrow().isEmailVerified());

        assertEquals(1, userRepository.markEmailVerified(alice.getId()));
        assertEquals(0, userRepository.markEmailVerified("non-existent-id"));

        assertTrue(userRepository.findById(alice.getId()).orElseThrow().isEmailVerified());
        // bob is unaffected
        assertFalse(userRepository.findById(bob.getId()).orElseThrow().isEmailVerified());
    }

    @Test
    void markPhoneVerified() {
        assertFalse(userRepository.findById(alice.getId()).orElseThrow().isPhoneVerified());

        assertEquals(1, userRepository.markPhoneVerified(alice.getId()));
        assertEquals(0, userRepository.markPhoneVerified("non-existent-id"));

        assertTrue(userRepository.findById(alice.getId()).orElseThrow().isPhoneVerified());
        assertFalse(userRepository.findById(bob.getId()).orElseThrow().isPhoneVerified());
    }

    @Test
    void findByEmailVerifiedAndPhoneVerified() {
        // both start unverified
        assertEquals(2, userRepository.findByEmailVerifiedAndActiveTrue(false, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(0, userRepository.findByEmailVerifiedAndActiveTrue(true,  PageRequest.of(0, 10)).getTotalElements());

        userRepository.markEmailVerified(alice.getId());

        Page<User> unverifiedEmail = userRepository.findByEmailVerifiedAndActiveTrue(false, PageRequest.of(0, 10));
        assertEquals(1, unverifiedEmail.getTotalElements());
        assertEquals(bob.getId(), unverifiedEmail.getContent().get(0).getId());

        Page<User> verifiedEmail = userRepository.findByEmailVerifiedAndActiveTrue(true, PageRequest.of(0, 10));
        assertEquals(1, verifiedEmail.getTotalElements());
        assertEquals(alice.getId(), verifiedEmail.getContent().get(0).getId());

        userRepository.markPhoneVerified(alice.getId());

        Page<User> verifiedPhone = userRepository.findByPhoneVerifiedAndActiveTrue(true,  PageRequest.of(0, 10));
        assertEquals(1, verifiedPhone.getTotalElements());
        assertEquals(alice.getId(), verifiedPhone.getContent().get(0).getId());

        Page<User> unverifiedPhone = userRepository.findByPhoneVerifiedAndActiveTrue(false, PageRequest.of(0, 10));
        assertEquals(1, unverifiedPhone.getTotalElements());
        assertEquals(bob.getId(), unverifiedPhone.getContent().get(0).getId());

        // deactivated user must not appear in unverified cleanup lists
        bob.setActive(false);
        userRepository.save(bob);
        assertEquals(0, userRepository.findByEmailVerifiedAndActiveTrue(false, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(0, userRepository.findByPhoneVerifiedAndActiveTrue(false, PageRequest.of(0, 10)).getTotalElements());
    }

    // AppUserRepository

    @Test
    void appUserFindByUsername() {
        appUserRepository.save(AppUser.builder()
            .username("analyst01").email("analyst01@bank.internal")
            .password("hashed_secret").roles(Set.of("ROLE_ANALYST")).build());

        Optional<AppUser> found = appUserRepository.findByUsername("analyst01");
        assertTrue(found.isPresent());
        assertEquals("analyst01", found.get().getUsername());
        assertTrue(appUserRepository.existsByUsername("analyst01"));
        assertFalse(appUserRepository.existsByUsername("ghost"));
    }

    @Test
    void appUserFindByEmail() {
        appUserRepository.saveAndFlush(AppUser.builder()
            .username("analyst01").email("analyst01@bank.internal")
            .password("hashed_secret").roles(Set.of("ROLE_ANALYST")).build());

        assertTrue(appUserRepository.findByEmail("analyst01@bank.internal").isPresent());
        assertTrue(appUserRepository.existsByEmail("analyst01@bank.internal"));
        assertFalse(appUserRepository.existsByEmail("ghost@bank.internal"));
    }

    @Test
    void appUserLinkedUserId() {
        AppUser customerLogin = appUserRepository.saveAndFlush(AppUser.builder()
            .username("alice_login").email("alice@example.com")
            .password("hashed_password").roles(Set.of("ROLE_USER"))
            .linkedUserId(alice.getId()).build());

        assertTrue(appUserRepository.existsByLinkedUserId(alice.getId()));
        assertFalse(appUserRepository.existsByLinkedUserId("non-existent-user-id"));

        Optional<AppUser> found = appUserRepository.findByLinkedUserId(alice.getId());
        assertTrue(found.isPresent());
        assertEquals("alice_login", found.get().getUsername());
    }

    @Test
    void duplicateUsernameIsRejected() {
        appUserRepository.saveAndFlush(AppUser.builder()
            .username("analyst01").email("a1@bank.internal")
            .password("hashed_secret").roles(Set.of("ROLE_ANALYST")).build());

        assertThrows(Exception.class, () ->
            appUserRepository.saveAndFlush(AppUser.builder()
                .username("analyst01").email("a2@bank.internal")
                .password("another_hash").roles(Set.of("ROLE_ANALYST")).build()));
    }

    @Test
    void duplicateEmailIsRejectedOnAppUser() {
        appUserRepository.saveAndFlush(AppUser.builder()
            .username("analyst01").email("shared@bank.internal")
            .password("hash1").roles(Set.of("ROLE_ANALYST")).build());

        assertThrows(Exception.class, () ->
            appUserRepository.saveAndFlush(AppUser.builder()
                .username("analyst02").email("shared@bank.internal")
                .password("hash2").roles(Set.of("ROLE_ANALYST")).build()));
    }

    @Test
    void bruteForceProtection() {
        AppUser appUser = appUserRepository.saveAndFlush(AppUser.builder()
            .username("victim").email("victim@bank.internal")
            .password("hashed").roles(Set.of("ROLE_USER")).build());

        assertEquals(0, appUserRepository.findById(appUser.getId()).orElseThrow().getFailedLoginAttempts());

        assertEquals(1, appUserRepository.incrementFailedLoginAttempts(appUser.getId()));
        assertEquals(1, appUserRepository.incrementFailedLoginAttempts(appUser.getId()));
        assertEquals(2, appUserRepository.findById(appUser.getId()).orElseThrow().getFailedLoginAttempts());

        // returns 0 when the id doesn't exist — silent failure instead of exception
        assertEquals(0, appUserRepository.incrementFailedLoginAttempts("non-existent-id"));

        Instant lockUntil = Instant.now().plusSeconds(900); // 15 min
        assertEquals(1, appUserRepository.lockUntil(appUser.getId(), lockUntil));
        assertNotNull(appUserRepository.findById(appUser.getId()).orElseThrow().getLockedUntil());

        // Successful login clears counter and lock
        assertEquals(1, appUserRepository.recordSuccessfulLogin(appUser.getId(), Instant.now()));
        AppUser after = appUserRepository.findById(appUser.getId()).orElseThrow();
        assertEquals(0, after.getFailedLoginAttempts());
        assertNull(after.getLockedUntil());
        assertNotNull(after.getLastLoginAt());
    }

    // UserSessionRepository

    private AppUser savedAppUser() {
        return appUserRepository.saveAndFlush(AppUser.builder()
            .username("alice_login").email("alice@example.com")
            .password("hashed").roles(Set.of("ROLE_USER"))
            .linkedUserId(alice.getId()).build());
    }

    private UserSession buildSession(String appUserId, String device) {
        return UserSession.builder()
            .appUserId(appUserId)
            .refreshTokenHash("$2a$10$hash_" + device)
            .deviceFingerprint("fp_" + device)
            .deviceName(device)
            .ipAddressIssued("1.2.3.4")
            .lastSeenIp("1.2.3.4")
            .lastSeenAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(604800))
            .build();
    }

    @Test
    void sessionDefaultValues() {
        AppUser appUser = savedAppUser();
        UserSession session = userSessionRepository.saveAndFlush(
            buildSession(appUser.getId(), "iPhone"));

        assertTrue(session.isActive());
        assertNotNull(session.getCreatedAt());
    }

    @Test
    void findActiveSessionsByUser() {
        AppUser appUser = savedAppUser();
        userSessionRepository.save(buildSession(appUser.getId(), "iPhone"));
        userSessionRepository.save(buildSession(appUser.getId(), "MacBook"));
        userSessionRepository.flush();

        List<UserSession> active = userSessionRepository.findByAppUserIdAndActiveTrue(appUser.getId());
        assertEquals(2, active.size());
        assertEquals(2, userSessionRepository.countByAppUserIdAndActiveTrue(appUser.getId()));
    }

    @Test
    void findByRefreshTokenHash() {
        AppUser appUser = savedAppUser();
        userSessionRepository.saveAndFlush(buildSession(appUser.getId(), "iPhone"));

        Optional<UserSession> found = userSessionRepository.findByRefreshTokenHash("$2a$10$hash_iPhone");
        assertTrue(found.isPresent());
        assertEquals("iPhone", found.get().getDeviceName());

        assertTrue(userSessionRepository.findByRefreshTokenHash("$2a$10$wrong_hash").isEmpty());
    }

    @Test
    void findByIdAndAppUserId_ownershipCheck() {
        AppUser appUser = savedAppUser();
        UserSession session = userSessionRepository.saveAndFlush(buildSession(appUser.getId(), "iPhone"));

        // correct owner — found
        assertTrue(userSessionRepository.findByIdAndAppUserId(session.getId(), appUser.getId()).isPresent());
        // wrong owner — empty, not an error
        assertTrue(userSessionRepository.findByIdAndAppUserId(session.getId(), "wrong-app-user-id").isEmpty());
    }

    @Test
    void deactivateSingleSession() {
        AppUser appUser = savedAppUser();
        UserSession s1 = userSessionRepository.saveAndFlush(buildSession(appUser.getId(), "iPhone"));
        UserSession s2 = userSessionRepository.saveAndFlush(buildSession(appUser.getId(), "MacBook"));

        assertEquals(1, userSessionRepository.deactivateById(s1.getId()));
        assertEquals(0, userSessionRepository.deactivateById("non-existent-session-id"));

        assertFalse(userSessionRepository.findById(s1.getId()).orElseThrow().isActive());
        assertTrue(userSessionRepository.findById(s2.getId()).orElseThrow().isActive());
        assertEquals(1, userSessionRepository.countByAppUserIdAndActiveTrue(appUser.getId()));
    }

    @Test
    void deactivateAllSessions() {
        AppUser appUser = savedAppUser();
        userSessionRepository.save(buildSession(appUser.getId(), "iPhone"));
        userSessionRepository.save(buildSession(appUser.getId(), "MacBook"));
        userSessionRepository.flush();

        int rows = userSessionRepository.deactivateAllByAppUserId(appUser.getId());
        assertEquals(2, rows);
        assertEquals(0, userSessionRepository.countByAppUserIdAndActiveTrue(appUser.getId()));

        // calling again on already-inactive sessions returns 0
        assertEquals(0, userSessionRepository.deactivateAllByAppUserId(appUser.getId()));
    }

    @Test
    void updateLastSeen() {
        AppUser appUser = savedAppUser();
        UserSession session = userSessionRepository.saveAndFlush(buildSession(appUser.getId(), "iPhone"));

        Instant newTime = Instant.now().plusSeconds(60);
        int rows = userSessionRepository.updateLastSeen(session.getId(), "9.9.9.9", newTime);
        assertEquals(1, rows);

        UserSession updated = userSessionRepository.findById(session.getId()).orElseThrow();
        assertEquals("9.9.9.9", updated.getLastSeenIp());
        assertEquals(newTime.getEpochSecond(), updated.getLastSeenAt().getEpochSecond(), 1);

        // non-existent session returns 0
        assertEquals(0, userSessionRepository.updateLastSeen("no-such-id", "1.1.1.1", newTime));
    }

    @Test
    void deleteExpiredSessions() {
        AppUser appUser = savedAppUser();

        // active session — must NOT be deleted
        userSessionRepository.saveAndFlush(buildSession(appUser.getId(), "iPhone"));

        // expired inactive session — must be deleted
        UserSession expired = UserSession.builder()
            .appUserId(appUser.getId())
            .refreshTokenHash("$2a$10$expired_hash")
            .deviceName("OldPhone")
            .ipAddressIssued("1.2.3.4").lastSeenIp("1.2.3.4").lastSeenAt(Instant.now())
            .expiresAt(Instant.now().minusSeconds(3600))
            .active(false)
            .build();
        userSessionRepository.saveAndFlush(expired);

        assertEquals(2, userSessionRepository.count());

        int deleted = userSessionRepository.deleteExpiredSessions(Instant.now());
        assertEquals(1, deleted);
        assertEquals(1, userSessionRepository.count());
    }

    // Transaction entity constraints

    @Test
    void transactionDefaultValues() {
        Transaction txn = transactionRepository.saveAndFlush(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99"))
            .location("New York").country("US").build());

        assertEquals(TransactionStatus.PENDING, txn.getStatus());
        assertEquals(RiskLevel.LOW, txn.getRiskLevel());
        assertEquals(0.0, txn.getFraudScore());
        assertFalse(txn.isP2P());
        assertNotNull(txn.getCreatedAt());
    }

    @Test
    void transactionWithoutMerchantOrReceiverIsRejected() {
        assertThrows(Exception.class, () ->
            transactionRepository.saveAndFlush(Transaction.builder()
                .sender(alice).amount(new BigDecimal("100.00"))
                .location("New York").country("US").build()));
    }

    @Test
    void transactionWithBothMerchantAndReceiverIsRejected() {
        assertThrows(Exception.class, () ->
            transactionRepository.saveAndFlush(Transaction.builder()
                .sender(alice).merchant("Amazon").receiver(bob)
                .amount(new BigDecimal("100.00"))
                .location("New York").country("US").build()));
    }

    @Test
    void transactionPreUpdateValidationTriggered() {
        // Persist a valid merchant payment, then mutate it to also have a receiver — @PreUpdate must reject
        Transaction txn = transactionRepository.saveAndFlush(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99")).location("New York").country("US").build());
        txn.setReceiver(bob);
        assertThrows(Exception.class, () -> transactionRepository.saveAndFlush(txn));
    }

    @Test
    void p2pTransactionPersists() {
        Transaction p2p = transactionRepository.save(Transaction.builder()
            .sender(alice).receiver(bob)
            .amount(new BigDecimal("200.00"))
            .location("New York").country("US").build());

        assertTrue(p2p.isP2P());
        assertNull(p2p.getMerchant());

        Transaction loaded = transactionRepository.findById(p2p.getId()).orElseThrow();
        assertTrue(loaded.isP2P());
        assertEquals(bob.getId(), loaded.getReceiver().getId());
    }

    // TransactionRepository

    @Test
    void findBySenderIdAndPages() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99")).location("New York").country("US").build());
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Walmart")
            .amount(new BigDecimal("120.00")).location("New York").country("US").build());
        transactionRepository.save(Transaction.builder()
            .sender(bob).merchant("Amazon")
            .amount(new BigDecimal("30.00")).location("Boston").country("US").build());

        assertEquals(2, transactionRepository.findBySenderId(alice.getId(), PageRequest.of(0, 10)).getTotalElements());

        // admin-only — sees all 3
        assertEquals(3, transactionRepository.findAllByStatus(TransactionStatus.PENDING, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(3, transactionRepository.findAllByRiskLevel(RiskLevel.LOW,  PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void userScopedStatusAndRiskFilter() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99")).location("New York").country("US")
            .status(TransactionStatus.APPROVED).build());
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("ShadyStore")
            .amount(new BigDecimal("500.00")).location("New York").country("US")
            .status(TransactionStatus.DECLINED).riskLevel(RiskLevel.HIGH).build());
        transactionRepository.save(Transaction.builder()
            .sender(bob).merchant("Amazon")
            .amount(new BigDecimal("30.00")).location("Boston").country("US")
            .status(TransactionStatus.DECLINED).build());

        // Alice sees only her declined transaction, not Bob's
        Page<Transaction> aliceDeclined = transactionRepository.findBySenderIdAndStatus(
            alice.getId(), TransactionStatus.DECLINED, PageRequest.of(0, 10));
        assertEquals(1, aliceDeclined.getTotalElements());
        assertEquals("ShadyStore", aliceDeclined.getContent().get(0).getMerchant());

        // Alice's high-risk filter
        Page<Transaction> aliceHighRisk = transactionRepository.findBySenderIdAndRiskLevel(
            alice.getId(), RiskLevel.HIGH, PageRequest.of(0, 10));
        assertEquals(1, aliceHighRisk.getTotalElements());
        assertEquals("ShadyStore", aliceHighRisk.getContent().get(0).getMerchant());
    }

    @Test
    void findByIdAndSenderId_ownershipCheck() {
        Transaction aliceTxn = transactionRepository.saveAndFlush(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99")).location("New York").country("US").build());

        // correct owner — found
        assertTrue(transactionRepository.findByIdAndSenderId(aliceTxn.getId(), alice.getId()).isPresent());
        // wrong owner — empty, not a 500
        assertTrue(transactionRepository.findByIdAndSenderId(aliceTxn.getId(), bob.getId()).isEmpty());
    }

    @Test
    void findBySenderIdAndCreatedAtBetween() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99")).location("New York").country("US").build());
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Walmart")
            .amount(new BigDecimal("120.00")).location("New York").country("US").build());

        List<Transaction> results = transactionRepository.findBySenderIdAndCreatedAtBetween(
            alice.getId(), Instant.now().minusSeconds(3600), Instant.now().plusSeconds(60));
        assertEquals(2, results.size());

        List<Transaction> empty = transactionRepository.findBySenderIdAndCreatedAtBetween(
            alice.getId(), Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3601));
        assertEquals(0, empty.size());
    }

    @Test
    void findRecentAndCount() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99")).location("New York").country("US").build());

        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        assertEquals(1, transactionRepository.findRecentBySender(alice.getId(), oneHourAgo).size());
        assertEquals(1, transactionRepository.countRecentBySender(alice.getId(), oneHourAgo));
    }

    @Test
    void sumAmountBySenderSince() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99")).location("New York").country("US").build());
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Scamsite")
            .amount(new BigDecimal("500.00")).location("New York").country("US")
            .status(TransactionStatus.FRAUD_BLOCKED).build());

        Optional<BigDecimal> total = transactionRepository.sumAmountBySenderSince(
            alice.getId(), Instant.now().minusSeconds(3600),
            List.of(TransactionStatus.DECLINED, TransactionStatus.FRAUD_BLOCKED));

        // FRAUD_BLOCKED is excluded — only 49.99 counts
        assertEquals(0, new BigDecimal("49.99").compareTo(total.orElse(BigDecimal.ZERO)));
    }

    @Test
    void sumAmountBySenderSince_noMatchingTransactions_returnsEmpty() {
        // bob has sent nothing — result must be Optional.empty(), not Optional(0)
        Optional<BigDecimal> empty = transactionRepository.sumAmountBySenderSince(
            bob.getId(), Instant.now().minusSeconds(3600),
            List.of());
        assertTrue(empty.isEmpty());
    }

    @Test
    void findRecentByMerchant() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99")).location("New York").country("US").build());
        transactionRepository.save(Transaction.builder()
            .sender(bob).merchant("Amazon")
            .amount(new BigDecimal("30.00")).location("Boston").country("US").build());
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Walmart")
            .amount(new BigDecimal("20.00")).location("New York").country("US").build());

        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        assertEquals(2, transactionRepository.findRecentByMerchant("Amazon", oneHourAgo).size());
        assertEquals(0, transactionRepository.findRecentByMerchant("Unknown", oneHourAgo).size());
    }

    @Test
    void findFlaggedTransactions() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("ShadyStore")
            .amount(new BigDecimal("999.00")).location("New York").country("US")
            .status(TransactionStatus.FLAGGED_FOR_REVIEW).fraudScore(72.0).build());
        transactionRepository.save(Transaction.builder()
            .sender(bob).merchant("Scamsite")
            .amount(new BigDecimal("1500.00")).location("Boston").country("US")
            .status(TransactionStatus.FRAUD_BLOCKED).fraudScore(91.0).build());

        Page<Transaction> queue = transactionRepository.findFlaggedTransactions(
            List.of(TransactionStatus.FLAGGED_FOR_REVIEW, TransactionStatus.FRAUD_BLOCKED),
            PageRequest.of(0, 10));

        assertEquals(2, queue.getTotalElements());
        // ordered by fraudScore DESC — Scamsite (91.0) must be first
        assertEquals("Scamsite",   queue.getContent().get(0).getMerchant());
        assertEquals("ShadyStore", queue.getContent().get(1).getMerchant());
    }

    @Test
    void existsBySenderIdAndMerchantAndCreatedAtAfter() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99")).location("New York").country("US").build());

        assertTrue(transactionRepository.existsBySenderIdAndMerchantAndCreatedAtAfter(
            alice.getId(), "Amazon", Instant.now().minusSeconds(60)));
        assertFalse(transactionRepository.existsBySenderIdAndMerchantAndCreatedAtAfter(
            alice.getId(), "Walmart", Instant.now().minusSeconds(60)));
    }

    @Test
    void findByReceiverId() {
        transactionRepository.saveAndFlush(Transaction.builder()
            .sender(alice).receiver(bob)
            .amount(new BigDecimal("100.00")).location("New York").country("US").build());
        transactionRepository.saveAndFlush(Transaction.builder()
            .sender(alice).receiver(bob)
            .amount(new BigDecimal("50.00")).location("New York").country("US").build());
        // alice's merchant payment — must NOT appear in bob's received list
        transactionRepository.saveAndFlush(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("30.00")).location("New York").country("US").build());

        Page<Transaction> bobReceived = transactionRepository.findByReceiverId(bob.getId(), PageRequest.of(0, 10));
        assertEquals(2, bobReceived.getTotalElements());
        assertTrue(bobReceived.getContent().stream().allMatch(t -> bob.getId().equals(t.getReceiver().getId())));

        // alice has received nothing
        assertEquals(0, transactionRepository.findByReceiverId(alice.getId(), PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void findTopSendersByVolumeSince() {
        // alice sends 300 total, bob sends 80 total
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("200.00")).location("New York").country("US").build());
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Walmart")
            .amount(new BigDecimal("100.00")).location("New York").country("US").build());
        transactionRepository.save(Transaction.builder()
            .sender(bob).merchant("Amazon")
            .amount(new BigDecimal("80.00")).location("Boston").country("US").build());
        transactionRepository.flush();

        List<Object[]> results = transactionRepository.findTopSendersByVolumeSince(
            Instant.now().minusSeconds(3600), PageRequest.of(0, 10));

        assertEquals(2, results.size());
        // ordered by SUM(amount) DESC — alice (300) must be first
        assertEquals(alice.getId(), results.get(0)[0]);
        assertEquals(0, new BigDecimal("300.00").compareTo((BigDecimal) results.get(0)[1]));
        assertEquals(2L, ((Number) results.get(0)[2]).longValue()); // COUNT

        assertEquals(bob.getId(), results.get(1)[0]);
        assertEquals(0, new BigDecimal("80.00").compareTo((BigDecimal) results.get(1)[1]));
    }

    @Test
    void findTopFraudMerchants() {
        for (int i = 0; i < 2; i++) {
            transactionRepository.save(Transaction.builder()
                .sender(alice).merchant("ShadyStore")
                .amount(new BigDecimal("500.00")).location("New York").country("US")
                .status(TransactionStatus.FRAUD_BLOCKED).build());
        }
        transactionRepository.save(Transaction.builder()
            .sender(bob).merchant("Amazon")
            .amount(new BigDecimal("100.00")).location("Boston").country("US")
            .status(TransactionStatus.FRAUD_BLOCKED).build());
        transactionRepository.flush();

        List<Object[]> results = transactionRepository.findTopFraudMerchants(Instant.now().minusSeconds(3600));
        assertEquals(2, results.size());
        assertEquals("ShadyStore", results.get(0)[0]);
        assertEquals(2L, ((Number) results.get(0)[1]).longValue());
        assertEquals("Amazon", results.get(1)[0]);
    }

    // MerchantBlacklistRepository

    @Test
    void merchantBlacklist() {
        merchantBlacklistRepository.save(MerchantBlacklist.builder()
            .merchantName("ShadyStore").reason("Multiple chargebacks").addedBy("analyst01").build());

        assertTrue(merchantBlacklistRepository.existsByMerchantNameAndActiveTrue("ShadyStore"));
        assertFalse(merchantBlacklistRepository.existsByMerchantNameAndActiveTrue("Amazon"));

        Optional<MerchantBlacklist> entry = merchantBlacklistRepository.findByMerchantName("ShadyStore");
        assertTrue(entry.isPresent());
        assertEquals("Multiple chargebacks", entry.get().getReason());
        assertEquals("analyst01", entry.get().getAddedBy());
    }

    @Test
    void duplicateMerchantNameIsRejected() {
        merchantBlacklistRepository.saveAndFlush(MerchantBlacklist.builder()
            .merchantName("ShadyStore").reason("Chargebacks").addedBy("analyst01").build());

        assertThrows(Exception.class, () ->
            merchantBlacklistRepository.saveAndFlush(MerchantBlacklist.builder()
                .merchantName("ShadyStore").reason("Another reason").addedBy("analyst02").build()));
    }

    @Test
    void merchantBlacklistDefaultValues() {
        MerchantBlacklist entry = merchantBlacklistRepository.saveAndFlush(MerchantBlacklist.builder()
            .merchantName("TestStore").reason("Testing defaults").build());

        assertTrue(entry.isActive());
        assertNotNull(entry.getCreatedAt());
    }

    @Test
    void softDeletedMerchantDoesNotBlockPayments() {
        MerchantBlacklist entry = merchantBlacklistRepository.saveAndFlush(MerchantBlacklist.builder()
            .merchantName("SoftStore").reason("Testing soft delete").addedBy("analyst01").build());

        assertTrue(merchantBlacklistRepository.existsByMerchantNameAndActiveTrue("SoftStore"));
        assertTrue(merchantBlacklistRepository.findByMerchantNameAndActiveTrue("SoftStore").isPresent());

        // soft-delete: set active = false
        entry.setActive(false);
        merchantBlacklistRepository.saveAndFlush(entry);

        // active-filtered queries no longer see it — payment would not be blocked
        assertFalse(merchantBlacklistRepository.existsByMerchantNameAndActiveTrue("SoftStore"));
        assertTrue(merchantBlacklistRepository.findByMerchantNameAndActiveTrue("SoftStore").isEmpty());

        // unfiltered lookup still finds it for audit trail
        assertTrue(merchantBlacklistRepository.findByMerchantName("SoftStore").isPresent());
    }

    // FraudAlertRepository

    @Test
    void fraudAlertsBasic() {
        Transaction txn = transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("ShadyStore")
            .amount(new BigDecimal("500.00")).location("New York").country("US").build());

        fraudAlertRepository.save(FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.HIGH_AMOUNT).riskLevel(RiskLevel.HIGH)
            .scoreContribution(35.0).description("Amount exceeds threshold").build());
        fraudAlertRepository.save(FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.BLACKLISTED_MERCHANT).riskLevel(RiskLevel.CRITICAL)
            .scoreContribution(50.0).description("Merchant is blacklisted").build());

        assertEquals(2, fraudAlertRepository.findByTransactionId(txn.getId()).size());

        Page<FraudAlert> byUser = fraudAlertRepository.findByUserId(alice.getId(), PageRequest.of(0, 10));
        assertEquals(2, byUser.getTotalElements());
        assertTrue(byUser.getContent().stream().allMatch(a -> alice.getId().equals(a.getUserId())));

        // admin-only: all unresolved
        Page<FraudAlert> unresolved = fraudAlertRepository.findAllByResolved(false, PageRequest.of(0, 10));
        assertEquals(2, unresolved.getTotalElements());
        assertTrue(unresolved.getContent().stream().noneMatch(FraudAlert::isResolved));

        // user-scoped: alice's unresolved
        Page<FraudAlert> aliceUnresolved = fraudAlertRepository.findByUserIdAndResolved(alice.getId(), false, PageRequest.of(0, 10));
        assertEquals(2, aliceUnresolved.getTotalElements());
        assertTrue(aliceUnresolved.getContent().stream().allMatch(a -> alice.getId().equals(a.getUserId()) && !a.isResolved()));

        assertEquals(0, fraudAlertRepository.findByUserIdAndResolved(alice.getId(), true, PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void fraudAlertResolvedScoping() {
        Transaction txn = transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("ShadyStore")
            .amount(new BigDecimal("500.00")).location("New York").country("US").build());

        FraudAlert alert = fraudAlertRepository.saveAndFlush(FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.HIGH_AMOUNT).riskLevel(RiskLevel.HIGH)
            .scoreContribution(35.0).description("test").build());

        // mark resolved
        alert.setResolved(true);
        fraudAlertRepository.save(alert);

        Page<FraudAlert> resolved = fraudAlertRepository.findAllByResolved(true, PageRequest.of(0, 10));
        assertEquals(1, resolved.getTotalElements());
        assertTrue(resolved.getContent().get(0).isResolved());
        assertEquals(0, fraudAlertRepository.findAllByResolved(false, PageRequest.of(0, 10)).getTotalElements());

        // user-scoped view matches
        Page<FraudAlert> aliceResolved = fraudAlertRepository.findByUserIdAndResolved(alice.getId(), true, PageRequest.of(0, 10));
        assertEquals(1, aliceResolved.getTotalElements());
        assertEquals(alice.getId(), aliceResolved.getContent().get(0).getUserId());
        assertEquals(0, fraudAlertRepository.findByUserIdAndResolved(alice.getId(), false, PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void fraudAlertDefaultValues() {
        Transaction txn = transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("ShadyStore")
            .amount(new BigDecimal("500.00")).location("New York").country("US").build());

        FraudAlert alert = fraudAlertRepository.saveAndFlush(FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.HIGH_AMOUNT).riskLevel(RiskLevel.HIGH)
            .scoreContribution(35.0).description("Amount exceeds threshold").build());

        assertFalse(alert.isResolved());
        assertNotNull(alert.getCreatedAt());
    }

    @Test
    void findByRuleTypeSince() {
        Transaction txn = transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("ShadyStore")
            .amount(new BigDecimal("500.00")).location("New York").country("US").build());

        fraudAlertRepository.save(FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.HIGH_AMOUNT).riskLevel(RiskLevel.HIGH)
            .scoreContribution(35.0).description("Amount exceeds threshold").build());
        fraudAlertRepository.save(FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.VELOCITY_BREACH).riskLevel(RiskLevel.MEDIUM)
            .scoreContribution(20.0).description("Velocity limit reached").build());

        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        assertEquals(1, fraudAlertRepository.findByRuleTypeSince(FraudRuleType.HIGH_AMOUNT, oneHourAgo).size());
        assertEquals(0, fraudAlertRepository.findByRuleTypeSince(FraudRuleType.BLACKLISTED_MERCHANT, oneHourAgo).size());
    }

    @Test
    void fraudAlertCountAndRuleStats() {
        Transaction txn = transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("ShadyStore")
            .amount(new BigDecimal("500.00")).location("New York").country("US").build());

        fraudAlertRepository.save(FraudAlert.builder().transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.HIGH_AMOUNT).riskLevel(RiskLevel.HIGH)
            .scoreContribution(35.0).description("First high amount").build());
        fraudAlertRepository.save(FraudAlert.builder().transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.HIGH_AMOUNT).riskLevel(RiskLevel.HIGH)
            .scoreContribution(35.0).description("Second high amount").build());
        fraudAlertRepository.save(FraudAlert.builder().transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.VELOCITY_BREACH).riskLevel(RiskLevel.MEDIUM)
            .scoreContribution(20.0).description("Velocity limit").build());

        assertEquals(3, fraudAlertRepository.countByUserIdAndCreatedAtAfter(alice.getId(), Instant.now().minusSeconds(3600)));

        List<Object[]> breakdown = fraudAlertRepository.countByRuleTypeSince(Instant.now().minusSeconds(3600));
        assertEquals(2, breakdown.size());
        // ordered by count DESC — HIGH_AMOUNT (2) must be first
        assertEquals(FraudRuleType.HIGH_AMOUNT,    breakdown.get(0)[0]);
        assertEquals(2L,                            breakdown.get(0)[1]);
        assertEquals(FraudRuleType.VELOCITY_BREACH, breakdown.get(1)[0]);
        assertEquals(1L,                            breakdown.get(1)[1]);
    }

    @Test
    void orphanRemovalDeletesAlert() {
        Transaction txn = transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("ShadyStore")
            .amount(new BigDecimal("500.00")).location("New York").country("US").build());

        FraudAlert alert = FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.HIGH_AMOUNT).riskLevel(RiskLevel.HIGH)
            .scoreContribution(35.0).description("test").build();

        txn.getFraudAlerts().add(alert);
        txn = transactionRepository.saveAndFlush(txn);
        FraudAlert saved = txn.getFraudAlerts().get(0);

        assertTrue(fraudAlertRepository.existsById(saved.getId()));

        txn.getFraudAlerts().remove(saved);
        transactionRepository.saveAndFlush(txn);

        assertFalse(fraudAlertRepository.existsById(saved.getId()));
    }
}