package main.fraud;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import main.application.states.RiskLevel;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.fraud.rules.FraudRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates all FraudRule beans against a transaction.
 * Spring collects every @Component implementing FraudRule into the injected list automatically.
 *
 * Three optimizations over the naive implementation:
 * 1. Rule counters and analysis timers are pre-registered at startup — analyze() does a direct
 *    Map lookup instead of a registry lookup on every transaction.
 * 2. Short-circuit: once score >= shortCircuitScore, remaining rules are skipped.
 *    Cheap rules run first by priority, so graph traversal is skipped when
 *    early rules already confirm high enough risk.
 * 3. Analysis timers are pre-registered per RiskLevel — no Timer.builder() call in the hot path.
 */
@Service
@Slf4j
public class FraudDetectionEngine {

    private final List<FraudRule> rules;
    private final Map<String, Counter> triggeredCounters;
    private final Map<String, Counter> errorCounters;
    private final Map<RiskLevel, Timer> analysisTimers;

    @Value("${fraud.block.short-circuit-score:100.0}")
    private double shortCircuitScore;

    public FraudDetectionEngine(List<FraudRule> rules, MeterRegistry meterRegistry) {
        this.rules = rules.stream()
            .sorted(Comparator.comparingInt(FraudRule::getPriority))
            .toList();

        // Pre-register so every rule appears in /actuator/prometheus from startup,
        // even rules that have never fired. analyze() then does a direct Map read.
        Map<String, Counter> triggered = new HashMap<>();
        Map<String, Counter> errors = new HashMap<>();

        for (FraudRule rule : this.rules) {
            triggered.put(rule.getRuleName(),
                meterRegistry.counter("fraud.rule.triggered", "rule", rule.getRuleName()));

            errors.put(rule.getRuleName(),
                meterRegistry.counter("fraud.rule.error", "rule", rule.getRuleName()));
        }

        this.triggeredCounters = Collections.unmodifiableMap(triggered);
        this.errorCounters = Collections.unmodifiableMap(errors);

        // Pre-register one Timer per RiskLevel so analyze() does an O(1) map read
        // instead of calling Timer.builder().register() on every transaction.
        Map<RiskLevel, Timer> timers = new EnumMap<>(RiskLevel.class);

        for (RiskLevel level : RiskLevel.values()) {
            timers.put(level,
                Timer.builder("fraud.analysis.duration")
                    .tag("riskLevel", level.name())
                    .register(meterRegistry));
        }

        this.analysisTimers = Collections.unmodifiableMap(timers);

        log.info("Fraud engine initialized with {} rules in priority order: {}",
            this.rules.size(), this.rules.stream().map(FraudRule::getRuleName).toList());
    }

    public FraudAnalysisResult analyze(Transaction transaction) {
        long startNs = System.nanoTime();

        List<FraudAlert> allAlerts = new ArrayList<>();
        double totalScore = 0.0;

        for (FraudRule rule : rules) {
            // Once score reaches the configured short-circuit threshold,
            // remaining rules cannot change the risk decision enough to justify extra work.
            if (totalScore >= shortCircuitScore) {
                log.debug("Short-circuiting at score={} — skipping remaining rules from '{}'",
                    totalScore, rule.getRuleName());
                break;
            }

            try {
                List<FraudAlert> alerts = rule.evaluate(transaction);

                if (!alerts.isEmpty()) {
                    allAlerts.addAll(alerts);

                    totalScore += alerts.stream()
                        .mapToDouble(FraudAlert::getScoreContribution)
                        .sum();

                    triggeredCounters.get(rule.getRuleName()).increment(alerts.size());

                    log.debug("Rule '{}' triggered {} alert(s) for txn {}",
                        rule.getRuleName(), alerts.size(), transaction.getId());
                }
            } catch (Exception e) {
                log.error("Fraud rule '{}' failed for txn {}: {}",
                    rule.getRuleName(), transaction.getId(), e.getMessage(), e);

                errorCounters.get(rule.getRuleName()).increment();
            }
        }

        double cappedScore = Math.min(100.0, totalScore);
        RiskLevel riskLevel = RiskLevel.fromScore(cappedScore);

        long elapsedNs = System.nanoTime() - startNs;
        analysisTimers.get(riskLevel).record(elapsedNs, TimeUnit.NANOSECONDS);

        log.info("Fraud analysis complete for txn {}: score={}, risk={}, alerts={}, duration={}ms",
            transaction.getId(),
            String.format("%.1f", cappedScore),
            riskLevel,
            allAlerts.size(),
            TimeUnit.NANOSECONDS.toMillis(elapsedNs));

        return new FraudAnalysisResult(cappedScore, riskLevel, allAlerts);
    }

    public record FraudAnalysisResult(double fraudScore, RiskLevel riskLevel, List<FraudAlert> alerts) {

        public boolean isHighRisk() {
            return riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
        }

        public boolean isCritical() {
            return riskLevel == RiskLevel.CRITICAL;
        }
    }
}