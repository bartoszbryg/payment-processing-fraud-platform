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

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
public class RepositoryIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired FraudAlertRepository fraudAlertRepository;
    @Autowired MerchantBlacklistRepository merchantBlacklistRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setup() {
        alice = userRepository.saveAndFlush(User.builder()
            .name("Alice Smith").email("alice@example.com")
            .balance(new BigDecimal("2000.00"))
            .homeCountry("US").homeCity("New York").build());

        bob = userRepository.saveAndFlush(User.builder()
            .name("Bob Jones").email("bob@example.com")
            .balance(new BigDecimal("500.00"))
            .homeCountry("US").build());
    }

    // UserRepository

    @Test
    void findByEmail() {
        Optional<User> found = userRepository.findByEmail("alice@example.com");
        assertTrue(found.isPresent());
        assertEquals("Alice Smith", found.get().getName());

        Optional<User> missing = userRepository.findByEmail("ghost@example.com");
        assertTrue(missing.isEmpty());
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
        assertNotNull(alice.getCreatedAt());
    }

    @Test
    void duplicateEmailIsRejected() {
        assertThrows(Exception.class, () ->
            userRepository.saveAndFlush(User.builder()
                .name("Duplicate Alice")
                .email("alice@example.com")
                .balance(new BigDecimal("100.00"))
                .homeCountry("US").build()));
    }

    @Test
    void findByFlagged() {
        alice.setFlagged(true);
        userRepository.save(alice);

        Page<User> flagged = userRepository.findByFlagged(true, PageRequest.of(0, 10));
        assertEquals(1, flagged.getTotalElements());
        assertEquals("Alice Smith", flagged.getContent().get(0).getName());
    }

    @Test
    void findHighRiskUsers() {
        alice.setRiskScore(75.0);
        userRepository.save(alice);

        Page<User> highRisk = userRepository.findHighRiskUsers(50.0, PageRequest.of(0, 10));
        assertEquals(1, highRisk.getTotalElements());
        assertEquals("Alice Smith", highRisk.getContent().get(0).getName());

        Page<User> noneAbove90 = userRepository.findHighRiskUsers(90.0, PageRequest.of(0, 10));
        assertEquals(0, noneAbove90.getTotalElements());
    }

    @Test
    void deductBalance() {
        int success = userRepository.deductBalance(alice.getId(), new BigDecimal("100.00"));
        assertEquals(1, success);

        int failed = userRepository.deductBalance(alice.getId(), new BigDecimal("99999.00"));
        assertEquals(0, failed);

        // clearAutomatically = true on @Modifying evicts the cache — findById hits the DB
        User updated = userRepository.findById(alice.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("1900.00").compareTo(updated.getBalance()));
    }

    @Test
    void updateRiskProfile() {
        int updated = userRepository.updateRiskProfile(alice.getId(), 82.5, true);
        assertEquals(1, updated);

        int missing = userRepository.updateRiskProfile("non-existent-id", 50.0, false);
        assertEquals(0, missing);

        // clearAutomatically = true on @Modifying evicts the cache — findById hits the DB
        User result = userRepository.findById(alice.getId()).orElseThrow();
        assertEquals(82.5, result.getRiskScore());
        assertTrue(result.isFlagged());
    }

    // AppUserRepository

    @Test
    void appUserFindByUsername() {
        appUserRepository.save(AppUser.builder()
            .username("analyst01")
            .password("hashed_secret")
            .roles(java.util.Set.of("ROLE_ANALYST"))
            .build());

        Optional<AppUser> found = appUserRepository.findByUsername("analyst01");
        assertTrue(found.isPresent());
        assertEquals("analyst01", found.get().getUsername());

        assertTrue(appUserRepository.existsByUsername("analyst01"));
        assertFalse(appUserRepository.existsByUsername("ghost"));
    }

    @Test
    void duplicateUsernameIsRejected() {
        appUserRepository.saveAndFlush(AppUser.builder()
            .username("analyst01")
            .password("hashed_secret")
            .roles(java.util.Set.of("ROLE_ANALYST"))
            .build());

        assertThrows(Exception.class, () ->
            appUserRepository.saveAndFlush(AppUser.builder()
                .username("analyst01")
                .password("another_hash")
                .roles(java.util.Set.of("ROLE_ANALYST"))
                .build()));
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
                .sender(alice)
                .amount(new BigDecimal("100.00"))
                .location("New York").country("US").build()));
    }

    @Test
    void transactionWithBothMerchantAndReceiverIsRejected() {
        assertThrows(Exception.class, () ->
            transactionRepository.saveAndFlush(Transaction.builder()
                .sender(alice)
                .merchant("Amazon")
                .receiver(bob)
                .amount(new BigDecimal("100.00"))
                .location("New York").country("US").build()));
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
            .amount(new BigDecimal("49.99"))
            .location("New York").country("US").build());

        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Walmart")
            .amount(new BigDecimal("120.00"))
            .location("New York").country("US").build());

        transactionRepository.save(Transaction.builder()
            .sender(bob).merchant("Amazon")
            .amount(new BigDecimal("30.00"))
            .location("Boston").country("US").build());

        Page<Transaction> aliceTxns = transactionRepository.findBySenderId(alice.getId(), PageRequest.of(0, 10));
        assertEquals(2, aliceTxns.getTotalElements());

        Page<Transaction> pending = transactionRepository.findByStatus(TransactionStatus.PENDING, PageRequest.of(0, 10));
        assertEquals(3, pending.getTotalElements());

        Page<Transaction> lowRisk = transactionRepository.findByRiskLevel(RiskLevel.LOW, PageRequest.of(0, 10));
        assertEquals(3, lowRisk.getTotalElements());
    }

    @Test
    void findBySenderIdAndCreatedAtBetween() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99"))
            .location("New York").country("US").build());

        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Walmart")
            .amount(new BigDecimal("120.00"))
            .location("New York").country("US").build());

        Instant from = Instant.now().minusSeconds(3600);
        Instant to   = Instant.now().plusSeconds(60);

        List<Transaction> results = transactionRepository
            .findBySenderIdAndCreatedAtBetween(alice.getId(), from, to);
        assertEquals(2, results.size());

        List<Transaction> empty = transactionRepository
            .findBySenderIdAndCreatedAtBetween(alice.getId(),
                Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3601));
        assertEquals(0, empty.size());
    }

    @Test
    void findRecentAndCount() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99"))
            .location("New York").country("US").build());

        Instant oneHourAgo = Instant.now().minusSeconds(3600);

        List<Transaction> recent = transactionRepository.findRecentBySender(alice.getId(), oneHourAgo);
        assertEquals(1, recent.size());

        long count = transactionRepository.countRecentBySender(alice.getId(), oneHourAgo);
        assertEquals(1, count);
    }

    @Test
    void sumAmountBySenderSince() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99"))
            .location("New York").country("US").build());

        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Scamsite")
            .amount(new BigDecimal("500.00"))
            .location("New York").country("US")
            .status(TransactionStatus.FRAUD_BLOCKED).build());

        Instant oneHourAgo = Instant.now().minusSeconds(3600);

        Optional<BigDecimal> total = transactionRepository.sumAmountBySenderSince(
            alice.getId(), oneHourAgo,
            List.of(TransactionStatus.DECLINED, TransactionStatus.FRAUD_BLOCKED));

        // 500.00 FRAUD_BLOCKED is excluded, only 49.99 counts
        assertEquals(0, new BigDecimal("49.99").compareTo(total.orElse(BigDecimal.ZERO)));
    }

    @Test
    void findRecentByMerchant() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99"))
            .location("New York").country("US").build());

        transactionRepository.save(Transaction.builder()
            .sender(bob).merchant("Amazon")
            .amount(new BigDecimal("30.00"))
            .location("Boston").country("US").build());

        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Walmart")
            .amount(new BigDecimal("20.00"))
            .location("New York").country("US").build());

        Instant oneHourAgo = Instant.now().minusSeconds(3600);

        List<Transaction> amazonTxns = transactionRepository.findRecentByMerchant("Amazon", oneHourAgo);
        assertEquals(2, amazonTxns.size());

        List<Transaction> unknownMerchant = transactionRepository.findRecentByMerchant("Unknown", oneHourAgo);
        assertEquals(0, unknownMerchant.size());
    }

    @Test
    void findFlaggedTransactions() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("ShadyStore")
            .amount(new BigDecimal("999.00"))
            .location("New York").country("US")
            .status(TransactionStatus.FLAGGED_FOR_REVIEW)
            .fraudScore(72.0).build());

        transactionRepository.save(Transaction.builder()
            .sender(bob).merchant("Scamsite")
            .amount(new BigDecimal("1500.00"))
            .location("Boston").country("US")
            .status(TransactionStatus.FRAUD_BLOCKED)
            .fraudScore(91.0).build());

        Page<Transaction> queue = transactionRepository.findFlaggedTransactions(
            List.of(TransactionStatus.FLAGGED_FOR_REVIEW, TransactionStatus.FRAUD_BLOCKED),
            PageRequest.of(0, 10));

        assertEquals(2, queue.getTotalElements());
        // ordered by fraudScore DESC — Scamsite (91.0) must be first
        assertEquals("Scamsite", queue.getContent().get(0).getMerchant());
        assertEquals("ShadyStore", queue.getContent().get(1).getMerchant());
    }

    @Test
    void existsBySenderIdAndMerchantAndCreatedAtAfter() {
        transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("49.99"))
            .location("New York").country("US").build());

        assertTrue(transactionRepository.existsBySenderIdAndMerchantAndCreatedAtAfter(
            alice.getId(), "Amazon", Instant.now().minusSeconds(60)));

        assertFalse(transactionRepository.existsBySenderIdAndMerchantAndCreatedAtAfter(
            alice.getId(), "Walmart", Instant.now().minusSeconds(60)));
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

        List<Object[]> results = transactionRepository
            .findTopFraudMerchants(Instant.now().minusSeconds(3600));

        assertEquals(2, results.size());
        assertEquals("ShadyStore", results.get(0)[0]);
        assertEquals(2L, ((Number) results.get(0)[1]).longValue());
        assertEquals("Amazon", results.get(1)[0]);
    }

    // MerchantBlacklistRepository

    @Test
    void merchantBlacklist() {
        merchantBlacklistRepository.save(MerchantBlacklist.builder()
            .merchantName("ShadyStore")
            .reason("Multiple chargebacks")
            .addedBy("analyst01")
            .build());

        assertTrue(merchantBlacklistRepository.existsByMerchantName("ShadyStore"));
        assertFalse(merchantBlacklistRepository.existsByMerchantName("Amazon"));

        Optional<MerchantBlacklist> entry = merchantBlacklistRepository.findByMerchantName("ShadyStore");
        assertTrue(entry.isPresent());
        assertEquals("Multiple chargebacks", entry.get().getReason());
        assertEquals("analyst01", entry.get().getAddedBy());
    }

    @Test
    void duplicateMerchantNameIsRejected() {
        merchantBlacklistRepository.saveAndFlush(MerchantBlacklist.builder()
            .merchantName("ShadyStore")
            .reason("Multiple chargebacks")
            .addedBy("analyst01")
            .build());

        assertThrows(Exception.class, () ->
            merchantBlacklistRepository.saveAndFlush(MerchantBlacklist.builder()
                .merchantName("ShadyStore")
                .reason("Another reason")
                .addedBy("analyst02")
                .build()));
    }

    @Test
    void merchantBlacklistDefaultValues() {
        MerchantBlacklist entry = merchantBlacklistRepository.saveAndFlush(MerchantBlacklist.builder()
            .merchantName("TestStore")
            .reason("Testing defaults")
            .build());

        assertTrue(entry.isActive());
        assertNotNull(entry.getCreatedAt());
    }

    // FraudAlertRepository

    @Test
    void fraudAlertsBasic() {
        Transaction txn = transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("ShadyStore")
            .amount(new BigDecimal("500.00"))
            .location("New York").country("US").build());

        fraudAlertRepository.save(FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.HIGH_AMOUNT).riskLevel(RiskLevel.HIGH)
            .scoreContribution(35.0).description("Amount exceeds threshold").build());

        fraudAlertRepository.save(FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.BLACKLISTED_MERCHANT).riskLevel(RiskLevel.CRITICAL)
            .scoreContribution(50.0).description("Merchant is blacklisted").build());

        List<FraudAlert> alerts = fraudAlertRepository.findByTransactionId(txn.getId());
        assertEquals(2, alerts.size());

        Page<FraudAlert> userAlerts = fraudAlertRepository.findByUserId(alice.getId(), PageRequest.of(0, 10));
        assertEquals(2, userAlerts.getTotalElements());

        // unresolved
        Page<FraudAlert> unresolved = fraudAlertRepository.findByResolved(false, PageRequest.of(0, 10));
        assertEquals(2, unresolved.getTotalElements());

        // resolved — mark one and verify
        FraudAlert first = alerts.get(0);
        first.setResolved(true);
        fraudAlertRepository.save(first);

        Page<FraudAlert> resolved = fraudAlertRepository.findByResolved(true, PageRequest.of(0, 10));
        assertEquals(1, resolved.getTotalElements());
    }

    @Test
    void fraudAlertDefaultValues() {
        Transaction txn = transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("ShadyStore")
            .amount(new BigDecimal("500.00"))
            .location("New York").country("US").build());

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
            .amount(new BigDecimal("500.00"))
            .location("New York").country("US").build());

        fraudAlertRepository.save(FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.HIGH_AMOUNT).riskLevel(RiskLevel.HIGH)
            .scoreContribution(35.0).description("Amount exceeds threshold").build());

        fraudAlertRepository.save(FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.VELOCITY_BREACH).riskLevel(RiskLevel.MEDIUM)
            .scoreContribution(20.0).description("Velocity limit reached").build());

        Instant oneHourAgo = Instant.now().minusSeconds(3600);

        List<FraudAlert> highAmountAlerts = fraudAlertRepository
            .findByRuleTypeSince(FraudRuleType.HIGH_AMOUNT, oneHourAgo);
        assertEquals(1, highAmountAlerts.size());
        assertEquals(FraudRuleType.HIGH_AMOUNT, highAmountAlerts.get(0).getRuleType());

        List<FraudAlert> blacklistAlerts = fraudAlertRepository
            .findByRuleTypeSince(FraudRuleType.BLACKLISTED_MERCHANT, oneHourAgo);
        assertEquals(0, blacklistAlerts.size());
    }

    @Test
    void fraudAlertCountAndRuleStats() {
        Transaction txn = transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("ShadyStore")
            .amount(new BigDecimal("500.00"))
            .location("New York").country("US").build());

        fraudAlertRepository.save(FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.HIGH_AMOUNT).riskLevel(RiskLevel.HIGH)
            .scoreContribution(35.0).description("Amount exceeds threshold").build());

        fraudAlertRepository.save(FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.HIGH_AMOUNT).riskLevel(RiskLevel.HIGH)
            .scoreContribution(35.0).description("Second high amount alert").build());

        fraudAlertRepository.save(FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.VELOCITY_BREACH).riskLevel(RiskLevel.MEDIUM)
            .scoreContribution(20.0).description("Velocity limit reached").build());

        long recentCount = fraudAlertRepository.countByUserIdAndCreatedAtAfter(
            alice.getId(), Instant.now().minusSeconds(3600));
        assertEquals(3, recentCount);

        List<Object[]> breakdown = fraudAlertRepository.countByRuleTypeSince(
            Instant.now().minusSeconds(3600));
        assertEquals(2, breakdown.size());
        // ordered by count DESC — HIGH_AMOUNT (2) must be first
        assertEquals(FraudRuleType.HIGH_AMOUNT, breakdown.get(0)[0]);
        assertEquals(2L, breakdown.get(0)[1]);
        assertEquals(FraudRuleType.VELOCITY_BREACH, breakdown.get(1)[0]);
        assertEquals(1L, breakdown.get(1)[1]);
    }

    @Test
    void orphanRemovalDeletesAlert() {
        Transaction txn = transactionRepository.save(Transaction.builder()
            .sender(alice).merchant("ShadyStore")
            .amount(new BigDecimal("500.00"))
            .location("New York").country("US").build());

        // Add to the collection BEFORE saving — Hibernate tracks it from the start
        FraudAlert alert = FraudAlert.builder()
            .transaction(txn).userId(alice.getId())
            .ruleType(FraudRuleType.HIGH_AMOUNT).riskLevel(RiskLevel.HIGH)
            .scoreContribution(35.0).description("test").build();

        txn.getFraudAlerts().add(alert);
        // saveAndFlush returns the managed entity; the original alert reference won't have its ID set
        txn = transactionRepository.saveAndFlush(txn);
        FraudAlert savedAlert = txn.getFraudAlerts().get(0);

        String alertId = savedAlert.getId();
        assertTrue(fraudAlertRepository.existsById(alertId)); // confirm it was saved

        // Now remove from the tracked collection — Hibernate sees [alert] → [] and fires DELETE
        txn.getFraudAlerts().remove(savedAlert);
        transactionRepository.saveAndFlush(txn);

        assertFalse(fraudAlertRepository.existsById(alertId));
    }
}