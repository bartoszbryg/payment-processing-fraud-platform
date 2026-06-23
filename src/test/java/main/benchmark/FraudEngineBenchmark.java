package main.benchmark;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.databaseModel.User;
import main.fraud.FraudDetectionEngine;
import main.fraud.rules.FraudRule;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks FraudDetectionEngine orchestration overhead.
 *
 * Measured cases:
 * - allRules: all mock rules trigger
 * - shortCircuit: first rule reaches score cap and skips remaining rules
 * - noAlerts: all rules return empty lists
 * - singleCritical: one critical-scoring rule
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class FraudEngineBenchmark {

    private FraudDetectionEngine engineAllRules;
    private FraudDetectionEngine engineShortCircuit;
    private FraudDetectionEngine engineNoAlerts;
    private FraudDetectionEngine engineSingleCritical;

    private Transaction transaction;

    @Setup(Level.Trial)
    public void setup() {
        User alice = User.builder()
            .name("Alice")
            .email("alice@bench.com")
            .phoneNumber("+14155550001")
            .balance(new BigDecimal("5000.00"))
            .homeCountry("US")
            .homeCity("New York")
            .build();

        alice.setId("alice-bench-id");

        transaction = Transaction.builder()
            .id("txn-bench-001")
            .sender(alice)
            .merchant("BenchMerchant")
            .amount(new BigDecimal("100.00"))
            .status(TransactionStatus.PENDING)
            .location("New York")
            .country("US")
            .build();

        engineAllRules = buildEngine(100.0, rules(20.0, 20.0, 20.0, 20.0, 10.0));
        engineShortCircuit = buildEngine(100.0, rules(100.0, 20.0, 20.0, 20.0, 20.0));
        engineNoAlerts = buildEngine(100.0, silentRules(5));
        engineSingleCritical = buildEngine(100.0, rules(90.0));
    }

    @Benchmark
    public void allRules(Blackhole bh) {
        bh.consume(engineAllRules.analyze(transaction));
    }

    @Benchmark
    public void shortCircuit(Blackhole bh) {
        bh.consume(engineShortCircuit.analyze(transaction));
    }

    @Benchmark
    public void noAlerts(Blackhole bh) {
        bh.consume(engineNoAlerts.analyze(transaction));
    }

    @Benchmark
    public void singleCritical(Blackhole bh) {
        bh.consume(engineSingleCritical.analyze(transaction));
    }

    private FraudDetectionEngine buildEngine(double shortCircuit, List<FraudRule> ruleList) {
        FraudDetectionEngine engine = new FraudDetectionEngine(ruleList, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(engine, "shortCircuitScore", shortCircuit);
        return engine;
    }

    private List<FraudRule> rules(double... scores) {
        FraudRule[] result = new FraudRule[scores.length];

        for (int i = 0; i < scores.length; i++) {
            final double score = scores[i];
            final int idx = i;

            result[i] = new FraudRule() {
                @Override
                public String getRuleName() {
                    return "BENCH_RULE_" + idx;
                }

                @Override
                public int getPriority() {
                    return idx * 10;
                }

                @Override
                public List<FraudAlert> evaluate(Transaction transaction) {
                    return List.of(FraudAlert.builder()
                        .transaction(transaction)
                        .userId(transaction.getSender().getId())
                        .ruleType(FraudRuleType.HIGH_AMOUNT)
                        .riskLevel(RiskLevel.HIGH)
                        .scoreContribution(score)
                        .description("bench")
                        .build());
                }
            };
        }

        return List.of(result);
    }

    private List<FraudRule> silentRules(int count) {
        FraudRule[] result = new FraudRule[count];

        for (int i = 0; i < count; i++) {
            final int idx = i;

            result[i] = new FraudRule() {
                @Override
                public String getRuleName() {
                    return "SILENT_RULE_" + idx;
                }

                @Override
                public int getPriority() {
                    return idx * 10;
                }

                @Override
                public List<FraudAlert> evaluate(Transaction transaction) {
                    return List.of();
                }
            };
        }

        return List.of(result);
    }
}