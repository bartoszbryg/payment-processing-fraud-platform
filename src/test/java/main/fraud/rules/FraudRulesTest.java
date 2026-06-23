package main.fraud.rules;

import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;
import main.databaseModel.FraudAlert;
import main.databaseModel.MerchantBlacklist;
import main.databaseModel.Transaction;
import main.databaseModel.User;
import main.fraud.cache.MerchantBlacklistCache;
import main.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for every FraudRule implementation.
 * Uses Mockito for repository/cache mocks — no Spring context or database needed.
 * ReflectionTestUtils sets @Value fields that Spring would normally inject.
 */
@ExtendWith(MockitoExtension.class)
class FraudRulesTest {

    // shared helpers

    private User user(String id, String homeCountry, String homeCity) {
        User u = User.builder()
            .name("Test User").email(id + "@example.com")
            .phoneNumber("+14155550001")
            .balance(new BigDecimal("5000.00"))
            .homeCountry(homeCountry).homeCity(homeCity)
            .build();
        u.setId(id);
        return u;
    }

    private Transaction merchantTxn(User sender, String merchant, BigDecimal amount,
                                    String country, String location) {
        return Transaction.builder()
            .id("txn-" + System.nanoTime())
            .sender(sender).merchant(merchant)
            .amount(amount).country(country).location(location)
            .status(TransactionStatus.PENDING).build();
    }

    private Transaction p2pTxn(User sender, User receiver, BigDecimal amount) {
        return Transaction.builder()
            .id("txn-" + System.nanoTime())
            .sender(sender).receiver(receiver)
            .amount(amount).country("US").location("New York")
            .status(TransactionStatus.PENDING).build();
    }

    // HighAmountRule

    @Nested
    class HighAmountRuleTests {

        HighAmountRule rule;

        @BeforeEach
        void setup() {
            rule = new HighAmountRule();
            ReflectionTestUtils.setField(rule, "highThreshold", new BigDecimal("10000"));
            ReflectionTestUtils.setField(rule, "criticalThreshold", new BigDecimal("50000"));
        }

        @Test
        void belowThreshold_noAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("9999.99"), "US", "New York");

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertTrue(alerts.isEmpty());
        }

        @Test
        void betweenThresholds_highRiskAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("20000.00"), "US", "New York");

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertEquals(1, alerts.size());
            FraudAlert alert = alerts.get(0);
            assertEquals(FraudRuleType.HIGH_AMOUNT, alert.getRuleType());
            assertEquals(RiskLevel.HIGH, alert.getRiskLevel());
            assertTrue(alert.getScoreContribution() >= 15.0);
            assertTrue(alert.getScoreContribution() <= 25.0);
        }

        @Test
        void atExactHighThreshold_triggersHighAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("10000.00"), "US", "New York");

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertEquals(1, alerts.size());
            assertEquals(RiskLevel.HIGH, alerts.get(0).getRiskLevel());
            assertEquals(15.0, alerts.get(0).getScoreContribution(), 0.01);
        }

        @Test
        void aboveCriticalThreshold_criticalAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("75000.00"), "US", "New York");

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertEquals(1, alerts.size());
            FraudAlert alert = alerts.get(0);
            assertEquals(FraudRuleType.HIGH_AMOUNT, alert.getRuleType());
            assertEquals(RiskLevel.CRITICAL, alert.getRiskLevel());
            assertEquals(40.0, alert.getScoreContribution(), 0.001);
        }

        @Test
        void atExactCriticalThreshold_criticalAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("50000.00"), "US", "New York");

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertEquals(1, alerts.size());
            assertEquals(RiskLevel.CRITICAL, alerts.get(0).getRiskLevel());
        }

        @Test
        void ruleMetadata() {
            assertEquals("HIGH_AMOUNT", rule.getRuleName());
            assertEquals(5, rule.getPriority());
        }
    }

    // UnusualLocationRule

    @Nested
    class UnusualLocationRuleTests {

        UnusualLocationRule rule;

        @BeforeEach
        void setup() {
            rule = new UnusualLocationRule();
            ReflectionTestUtils.setField(rule, "highRiskCountriesConfig", "KP,IR,SY,CU,SD,MM,BY,RU");
            // manually invoke @PostConstruct — Spring does this automatically at runtime
            rule.init();
        }

        @Test
        void sameCountrySameCity_noAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("50.00"), "US", "New York");

            assertTrue(rule.evaluate(txn).isEmpty());
        }

        @Test
        void sameCountryDifferentCity_lowAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("50.00"), "US", "Los Angeles");

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertEquals(1, alerts.size());
            assertEquals(FraudRuleType.UNUSUAL_LOCATION, alerts.get(0).getRuleType());
            assertEquals(RiskLevel.LOW, alerts.get(0).getRiskLevel());
            assertEquals(5.0, alerts.get(0).getScoreContribution(), 0.001);
        }

        @Test
        void differentCountryNotHighRisk_mediumAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Shop", new BigDecimal("50.00"), "DE", "Berlin");

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertEquals(1, alerts.size());
            assertEquals(FraudRuleType.UNUSUAL_LOCATION, alerts.get(0).getRuleType());
            assertEquals(RiskLevel.MEDIUM, alerts.get(0).getRiskLevel());
            assertEquals(15.0, alerts.get(0).getScoreContribution(), 0.001);
        }

        @Test
        void highRiskCountry_criticalAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Shop", new BigDecimal("50.00"), "IR", "Tehran");

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertEquals(1, alerts.size());
            assertEquals(FraudRuleType.CROSS_BORDER_HIGH_RISK, alerts.get(0).getRuleType());
            assertEquals(RiskLevel.CRITICAL, alerts.get(0).getRiskLevel());
            assertEquals(35.0, alerts.get(0).getScoreContribution(), 0.001);
        }

        @Test
        void nullCountryOnTransaction_noAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("50.00"), null, "New York");

            assertTrue(rule.evaluate(txn).isEmpty());
        }

        @Test
        void highRiskCountryIsCaseInsensitive() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Shop", new BigDecimal("50.00"), "ru", "Moscow");

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertEquals(1, alerts.size());
            assertEquals(FraudRuleType.CROSS_BORDER_HIGH_RISK, alerts.get(0).getRuleType());
        }

        @Test
        void ruleMetadata() {
            assertEquals("UNUSUAL_LOCATION", rule.getRuleName());
            assertEquals(10, rule.getPriority());
        }
    }

    // BlacklistedMerchantRule

    @Nested
    class BlacklistedMerchantRuleTests {

        @Mock MerchantBlacklistCache blacklistCache;

        BlacklistedMerchantRule rule;

        @BeforeEach
        void setup() {
            rule = new BlacklistedMerchantRule(blacklistCache);
        }

        @Test
        void merchantNotBlacklisted_noAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("100.00"), "US", "New York");
            when(blacklistCache.findActive("Amazon")).thenReturn(Optional.empty());

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertTrue(alerts.isEmpty());
            verify(blacklistCache).findActive("Amazon");
        }

        @Test
        void merchantBlacklisted_criticalAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "ShadyStore", new BigDecimal("100.00"), "US", "New York");
            MerchantBlacklist entry = MerchantBlacklist.builder()
                .merchantName("ShadyStore").reason("Multiple chargebacks").addedBy("analyst01").build();
            when(blacklistCache.findActive("ShadyStore")).thenReturn(Optional.of(entry));

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertEquals(1, alerts.size());
            FraudAlert alert = alerts.get(0);
            assertEquals(FraudRuleType.BLACKLISTED_MERCHANT, alert.getRuleType());
            assertEquals(RiskLevel.CRITICAL, alert.getRiskLevel());
            assertEquals(100.0, alert.getScoreContribution(), 0.001);
            assertTrue(alert.getDescription().contains("Multiple chargebacks"));
        }

        @Test
        void p2pTransaction_skipsBlacklistCheck() {
            User alice = user("alice", "US", "New York");
            User bob   = user("bob",   "US", "Boston");
            Transaction txn = p2pTxn(alice, bob, new BigDecimal("200.00"));

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertTrue(alerts.isEmpty());
            verifyNoInteractions(blacklistCache); // no cache lookup for P2P
        }

        @Test
        void ruleMetadata() {
            assertEquals("BLACKLISTED_MERCHANT", rule.getRuleName());
            assertEquals(15, rule.getPriority());
        }
    }

    // RapidTransactionRule

    @Nested
    class RapidTransactionRuleTests {

        @Mock TransactionRepository transactionRepository;

        RapidTransactionRule rule;

        @BeforeEach
        void setup() {
            rule = new RapidTransactionRule(transactionRepository);
            ReflectionTestUtils.setField(rule, "windowMinutes", 5);
            ReflectionTestUtils.setField(rule, "maxCount", 3);
            ReflectionTestUtils.setField(rule, "criticalCount", 10);
        }

        @Test
        void belowThreshold_noAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("50.00"), "US", "New York");
            when(transactionRepository.countRecentBySender(eq(alice.getId()), any(Instant.class))).thenReturn(2L);

            assertTrue(rule.evaluate(txn).isEmpty());
        }

        @Test
        void atMaxCount_highAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("50.00"), "US", "New York");
            when(transactionRepository.countRecentBySender(eq(alice.getId()), any(Instant.class))).thenReturn(3L);

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertEquals(1, alerts.size());
            assertEquals(FraudRuleType.RAPID_TRANSACTIONS, alerts.get(0).getRuleType());
            assertEquals(RiskLevel.HIGH, alerts.get(0).getRiskLevel());
            assertTrue(alerts.get(0).getScoreContribution() >= 15.0);
        }

        @Test
        void atCriticalCount_criticalAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("50.00"), "US", "New York");
            when(transactionRepository.countRecentBySender(eq(alice.getId()), any(Instant.class))).thenReturn(10L);

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertEquals(1, alerts.size());
            assertEquals(RiskLevel.CRITICAL, alerts.get(0).getRiskLevel());
            assertEquals(45.0, alerts.get(0).getScoreContribution(), 0.001);
        }

        @Test
        void windowUsedForQuery() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("50.00"), "US", "New York");
            when(transactionRepository.countRecentBySender(eq(alice.getId()), any(Instant.class))).thenReturn(0L);

            rule.evaluate(txn);

            // capture the Instant passed — must be within the 5-minute window
            verify(transactionRepository).countRecentBySender(
                eq(alice.getId()),
                argThat(since -> since.isAfter(Instant.now().minus(6, ChronoUnit.MINUTES))
                              && since.isBefore(Instant.now().minus(4, ChronoUnit.MINUTES))));
        }

        @Test
        void ruleMetadata() {
            assertEquals("RAPID_TRANSACTIONS", rule.getRuleName());
            assertEquals(20, rule.getPriority());
        }
    }

    // VelocityBreachRule

    @Nested
    class VelocityBreachRuleTests {

        @Mock TransactionRepository transactionRepository;

        VelocityBreachRule rule;

        @BeforeEach
        void setup() {
            rule = new VelocityBreachRule(transactionRepository);
            ReflectionTestUtils.setField(rule, "dailyLimit",  new BigDecimal("20000"));
            ReflectionTestUtils.setField(rule, "hourlyLimit", new BigDecimal("5000"));
        }

        @Test
        void withinBothLimits_noAlert() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("100.00"), "US", "New York");
            when(transactionRepository.sumAmountBySenderSince(
                eq(alice.getId()), any(Instant.class), any(Collection.class)))
                .thenReturn(Optional.of(new BigDecimal("200.00")));

            assertTrue(rule.evaluate(txn).isEmpty());
        }

        @Test
        void hourlyLimitBreached_highAlert() {
            User alice = user("alice", "US", "New York");
            // alice has spent 4900 in the last hour; this transaction of 200 would push to 5100
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("200.00"), "US", "New York");
            when(transactionRepository.sumAmountBySenderSince(
                eq(alice.getId()), any(Instant.class), any(Collection.class)))
                .thenReturn(Optional.of(new BigDecimal("4900.00")));

            List<FraudAlert> alerts = rule.evaluate(txn);

            // at least one alert for hourly breach
            assertTrue(alerts.stream().anyMatch(a -> a.getRuleType() == FraudRuleType.VELOCITY_BREACH));
            assertTrue(alerts.stream().anyMatch(a -> a.getDescription().contains("hourly")));
        }

        @Test
        void dailyLimitBreached_highAlert() {
            User alice = user("alice", "US", "New York");
            // daily breach but within hourly — mock returns different amounts for each call
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("1000.00"), "US", "New York");
            when(transactionRepository.sumAmountBySenderSince(
                eq(alice.getId()), any(Instant.class), any(Collection.class)))
                .thenReturn(Optional.of(new BigDecimal("500.00")))   // hourly spend — within limit
                .thenReturn(Optional.of(new BigDecimal("19500.00"))); // daily spend — breach on second call

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertTrue(alerts.stream().anyMatch(a -> a.getDescription().contains("daily")));
        }

        @Test
        void doubleOverage_criticalRisk() {
            User alice = user("alice", "US", "New York");
            // projected daily spend > 2x limit → CRITICAL
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("20000.00"), "US", "New York");
            when(transactionRepository.sumAmountBySenderSince(
                eq(alice.getId()), any(Instant.class), any(Collection.class)))
                .thenReturn(Optional.of(new BigDecimal("21000.00"))); // already over limit

            List<FraudAlert> alerts = rule.evaluate(txn);

            assertTrue(alerts.stream().anyMatch(a -> a.getRiskLevel() == RiskLevel.CRITICAL));
        }

        @Test
        void noSpendHistory_emptyOptional_isHandledGracefully() {
            User alice = user("alice", "US", "New York");
            Transaction txn = merchantTxn(alice, "Amazon", new BigDecimal("100.00"), "US", "New York");
            when(transactionRepository.sumAmountBySenderSince(
                eq(alice.getId()), any(Instant.class), any(Collection.class)))
                .thenReturn(Optional.empty());

            // no prior spend — well within limits — no alert
            assertTrue(rule.evaluate(txn).isEmpty());
        }

        @Test
        void ruleMetadata() {
            assertEquals("VELOCITY_BREACH", rule.getRuleName());
            assertEquals(30, rule.getPriority());
        }
    }
}