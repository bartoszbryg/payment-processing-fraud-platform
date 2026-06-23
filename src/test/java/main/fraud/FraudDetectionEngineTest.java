package main.fraud;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.databaseModel.User;
import main.fraud.rules.FraudRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FraudDetectionEngine — the orchestrator.
 *
 * Uses a real SimpleMeterRegistry (in-memory, no Prometheus endpoint) so metric
 * assertions are actual counter reads rather than mock verifications.
 * FraudRule implementations are Mockito mocks configured per test.
 */
@ExtendWith(MockitoExtension.class)
class FraudDetectionEngineTest {

    MeterRegistry meterRegistry;
    Transaction   transaction;

    @BeforeEach
    void setup() {
        meterRegistry = new SimpleMeterRegistry();

        User alice = User.builder()
            .name("Alice").email("alice@example.com")
            .phoneNumber("+14155550001")
            .balance(new BigDecimal("5000.00"))
            .homeCountry("US").homeCity("New York")
            .build();
        alice.setId("alice-id");

        transaction = Transaction.builder()
            .id("txn-001")
            .sender(alice).merchant("Amazon")
            .amount(new BigDecimal("100.00"))
            .status(TransactionStatus.PENDING)
            .location("New York").country("US")
            .build();
    }

    // helper: build a mock FraudRule

    private FraudRule mockRule(String name, int priority, List<FraudAlert> alerts) {
        FraudRule rule = mock(FraudRule.class);
        when(rule.getRuleName()).thenReturn(name);
        lenient().when(rule.getPriority()).thenReturn(priority);
        lenient().when(rule.evaluate(any(Transaction.class))).thenReturn(alerts);
        return rule;
    }

    private FraudAlert alert(double score) {
        return FraudAlert.builder()
            .transaction(transaction)
            .userId("alice-id")
            .ruleType(FraudRuleType.HIGH_AMOUNT)
            .riskLevel(RiskLevel.HIGH)
            .scoreContribution(score)
            .description("test alert")
            .build();
    }

    private FraudDetectionEngine engine(List<FraudRule> rules) {
        FraudDetectionEngine engine = new FraudDetectionEngine(rules, meterRegistry);
        ReflectionTestUtils.setField(engine, "shortCircuitScore", 100.0);
        return engine;
    }

    // scoring

    @Nested
    class ScoringTests {

        @Test
        void noRules_scoreZeroLowRisk() {
            FraudDetectionEngine engine = engine(List.of());
            FraudDetectionEngine.FraudAnalysisResult result = engine.analyze(transaction);

            assertEquals(0.0, result.fraudScore(), 0.001);
            assertEquals(RiskLevel.LOW, result.riskLevel());
            assertTrue(result.alerts().isEmpty());
        }

        @Test
        void ruleWithNoAlerts_scoreZero() {
            FraudRule rule = mockRule("NO_OP", 10, List.of());
            FraudDetectionEngine engine = engine(List.of(rule));

            FraudDetectionEngine.FraudAnalysisResult result = engine.analyze(transaction);

            assertEquals(0.0, result.fraudScore(), 0.001);
            assertEquals(RiskLevel.LOW, result.riskLevel());
        }

        @Test
        void singleRuleWithScore_accumulatesCorrectly() {
            FraudRule rule = mockRule("HIGH_AMOUNT", 10, List.of(alert(40.0)));
            FraudDetectionEngine engine = engine(List.of(rule));

            FraudDetectionEngine.FraudAnalysisResult result = engine.analyze(transaction);

            assertEquals(40.0, result.fraudScore(), 0.001);
            // 40 maps to MEDIUM (31-60) per RiskLevel enum
            assertEquals(RiskLevel.MEDIUM, result.riskLevel());
            assertEquals(1, result.alerts().size());
        }

        @Test
        void multipleRules_scoresAccumulate() {
            FraudRule r1 = mockRule("RULE_A", 10, List.of(alert(30.0)));
            FraudRule r2 = mockRule("RULE_B", 20, List.of(alert(40.0)));
            FraudDetectionEngine engine = engine(List.of(r1, r2));

            FraudDetectionEngine.FraudAnalysisResult result = engine.analyze(transaction);

            assertEquals(70.0, result.fraudScore(), 0.001);
            assertEquals(RiskLevel.HIGH, result.riskLevel()); // 61-85 = HIGH
            assertEquals(2, result.alerts().size());
        }

        @Test
        void scoreCappedAt100() {
            FraudRule r1 = mockRule("RULE_A", 10, List.of(alert(80.0)));
            FraudRule r2 = mockRule("RULE_B", 20, List.of(alert(80.0)));
            FraudDetectionEngine engine = engine(List.of(r1, r2));

            FraudDetectionEngine.FraudAnalysisResult result = engine.analyze(transaction);

            assertEquals(100.0, result.fraudScore(), 0.001);
            assertEquals(RiskLevel.CRITICAL, result.riskLevel());
        }

        @Test
        void riskLevelBoundaries() {
            // LOW: 0-30
            assertEquals(RiskLevel.LOW,      RiskLevel.fromScore(0));
            assertEquals(RiskLevel.LOW,      RiskLevel.fromScore(30));
            // MEDIUM: 31-60
            assertEquals(RiskLevel.MEDIUM,   RiskLevel.fromScore(31));
            assertEquals(RiskLevel.MEDIUM,   RiskLevel.fromScore(60));
            // HIGH: 61-85
            assertEquals(RiskLevel.HIGH,     RiskLevel.fromScore(61));
            assertEquals(RiskLevel.HIGH,     RiskLevel.fromScore(85));
            // CRITICAL: 86-100
            assertEquals(RiskLevel.CRITICAL, RiskLevel.fromScore(86));
            assertEquals(RiskLevel.CRITICAL, RiskLevel.fromScore(100));
        }
    }

    // convenience predicates on FraudAnalysisResult

    @Nested
    class ResultPredicateTests {

        @Test
        void isHighRisk_trueForHighAndCritical() {
            FraudRule highRule     = mockRule("R", 10, List.of(alert(70.0))); // HIGH (61-85)
            FraudRule criticalRule = mockRule("R", 10, List.of(alert(90.0))); // CRITICAL (86+)

            FraudDetectionEngine engineHigh     = engine(List.of(highRule));
            FraudDetectionEngine engineCritical = engine(List.of(criticalRule));

            assertTrue(engineHigh.analyze(transaction).isHighRisk());
            assertTrue(engineCritical.analyze(transaction).isHighRisk());
        }

        @Test
        void isHighRisk_falseForLowAndMedium() {
            FraudRule lowRule    = mockRule("R", 10, List.of()); // LOW
            FraudRule mediumRule = mockRule("R", 10, List.of(alert(40.0))); // MEDIUM

            assertFalse(engine(List.of(lowRule)).analyze(transaction).isHighRisk());
            assertFalse(engine(List.of(mediumRule)).analyze(transaction).isHighRisk());
        }

        @Test
        void isCritical_onlyForCritical() {
            FraudRule criticalRule = mockRule("R", 10, List.of(alert(90.0)));
            FraudRule highRule     = mockRule("R", 10, List.of(alert(70.0)));

            assertTrue(engine(List.of(criticalRule)).analyze(transaction).isCritical());
            assertFalse(engine(List.of(highRule)).analyze(transaction).isCritical());
        }
    }

    // rule execution order

    @Nested
    class PriorityOrderTests {

        @Test
        void rulesRunInPriorityOrder() {
            List<String> executionOrder = new ArrayList<>();

            FraudRule r50 = mock(FraudRule.class);
            when(r50.getRuleName()).thenReturn("RULE_50");
            when(r50.getPriority()).thenReturn(50);
            when(r50.evaluate(any())).thenAnswer(inv -> { executionOrder.add("RULE_50"); return List.of(); });

            FraudRule r10 = mock(FraudRule.class);
            when(r10.getRuleName()).thenReturn("RULE_10");
            when(r10.getPriority()).thenReturn(10);
            when(r10.evaluate(any())).thenAnswer(inv -> { executionOrder.add("RULE_10"); return List.of(); });

            FraudRule r30 = mock(FraudRule.class);
            when(r30.getRuleName()).thenReturn("RULE_30");
            when(r30.getPriority()).thenReturn(30);
            when(r30.evaluate(any())).thenAnswer(inv -> { executionOrder.add("RULE_30"); return List.of(); });

            // deliberately injected in wrong order to prove constructor sorts them
            FraudDetectionEngine eng = engine(List.of(r50, r10, r30));
            eng.analyze(transaction);

            assertEquals(List.of("RULE_10", "RULE_30", "RULE_50"), executionOrder);
        }

        @Test
        void shortCircuit_skipsRemainingRulesWhenScoreHitsThreshold() {
            FraudRule cheap     = mockRule("CHEAP",     5,  List.of(alert(100.0))); // fills score immediately
            FraudRule expensive = mockRule("EXPENSIVE", 50, List.of(alert(10.0)));

            FraudDetectionEngine eng = engine(List.of(cheap, expensive));
            eng.analyze(transaction);

            verify(cheap).evaluate(transaction);
            verify(expensive, never()).evaluate(any()); // short-circuited
        }

        @Test
        void shortCircuit_configuredAtLowerThreshold() {
            FraudRule first  = mockRule("FIRST",  5,  List.of(alert(86.0))); // hits CRITICAL threshold
            FraudRule second = mockRule("SECOND", 10, List.of(alert(10.0)));

            FraudDetectionEngine eng = new FraudDetectionEngine(List.of(first, second), meterRegistry);
            ReflectionTestUtils.setField(eng, "shortCircuitScore", 86.0); // skip at CRITICAL

            eng.analyze(transaction);

            verify(first).evaluate(transaction);
            verify(second, never()).evaluate(any());
        }
    }

    // fault tolerance

    @Nested
    class FaultToleranceTests {

        @Test
        void ruleFailure_doesNotStopOtherRules() {
            FraudRule failing = mockRule("FAILING", 5, List.of());
            when(failing.evaluate(any())).thenThrow(new RuntimeException("simulated rule failure"));

            FraudRule working = mockRule("WORKING", 10, List.of(alert(35.0)));

            FraudDetectionEngine eng = engine(List.of(failing, working));
            FraudDetectionEngine.FraudAnalysisResult result = eng.analyze(transaction);

            // working rule still runs and contributes its score
            assertEquals(35.0, result.fraudScore(), 0.001);
            assertEquals(1, result.alerts().size());
        }

        @Test
        void allRulesFail_returnsZeroScore() {
            FraudRule r1 = mockRule("R1", 5,  List.of());
            FraudRule r2 = mockRule("R2", 10, List.of());
            when(r1.evaluate(any())).thenThrow(new RuntimeException("r1 failed"));
            when(r2.evaluate(any())).thenThrow(new RuntimeException("r2 failed"));

            FraudDetectionEngine eng = engine(List.of(r1, r2));
            FraudDetectionEngine.FraudAnalysisResult result = eng.analyze(transaction);

            assertEquals(0.0, result.fraudScore(), 0.001);
            assertEquals(RiskLevel.LOW, result.riskLevel());
        }
    }

    // Micrometer metrics

    @Nested
    class MetricsTests {

        @Test
        void triggeredCounter_incrementedWhenRuleFires() {
            FraudRule rule = mockRule("HIGH_AMOUNT", 10, List.of(alert(40.0)));
            engine(List.of(rule)).analyze(transaction);

            double count = meterRegistry.counter("fraud.rule.triggered", "rule", "HIGH_AMOUNT").count();
            assertEquals(1.0, count, 0.001);
        }

        @Test
        void triggeredCounter_notIncrementedWhenRuleDoesNotFire() {
            FraudRule rule = mockRule("HIGH_AMOUNT", 10, List.of());
            engine(List.of(rule)).analyze(transaction);

            double count = meterRegistry.counter("fraud.rule.triggered", "rule", "HIGH_AMOUNT").count();
            assertEquals(0.0, count, 0.001);
        }

        @Test
        void errorCounter_incrementedOnRuleException() {
            FraudRule rule = mockRule("FAILING_RULE", 10, List.of());
            when(rule.evaluate(any())).thenThrow(new RuntimeException("boom"));

            engine(List.of(rule)).analyze(transaction);

            double count = meterRegistry.counter("fraud.rule.error", "rule", "FAILING_RULE").count();
            assertEquals(1.0, count, 0.001);
        }

        @Test
        void allRuleCountersPreRegistered_atStartup() {
            FraudRule r1 = mockRule("RULE_A", 5,  List.of());
            FraudRule r2 = mockRule("RULE_B", 10, List.of());
            engine(List.of(r1, r2)); // no analyze() call — just construction

            // counters must exist (at 0) even before any transaction is processed
            assertNotNull(meterRegistry.find("fraud.rule.triggered").tag("rule", "RULE_A").counter());
            assertNotNull(meterRegistry.find("fraud.rule.triggered").tag("rule", "RULE_B").counter());
            assertNotNull(meterRegistry.find("fraud.rule.error").tag("rule", "RULE_A").counter());
        }

        @Test
        void analysisTimer_recordedPerTransaction() {
            FraudRule rule = mockRule("RULE", 10, List.of(alert(40.0)));
            FraudDetectionEngine eng = engine(List.of(rule));

            eng.analyze(transaction);
            eng.analyze(transaction);

            var timer = meterRegistry.find("fraud.analysis.duration")
                .tag("riskLevel", RiskLevel.MEDIUM.name())
                .timer();

            assertNotNull(timer);
            assertEquals(2, timer.count());
        }

        @Test
        void multipleAlertsSameRule_counterIncrementsCorrectly() {
            FraudRule rule = mockRule("VELOCITY_BREACH", 10,
                List.of(alert(20.0), alert(25.0))); // two alerts from one rule
            engine(List.of(rule)).analyze(transaction);

            // counter.increment(alerts.size()) — should be 2
            double count = meterRegistry.counter("fraud.rule.triggered", "rule", "VELOCITY_BREACH").count();
            assertEquals(2.0, count, 0.001);
        }
    }
}