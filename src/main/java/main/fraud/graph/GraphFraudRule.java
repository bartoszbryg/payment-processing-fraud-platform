package main.fraud.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.fraud.rules.FraudRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts graph analysis signals into FraudAlerts.
 * Runs late so cheap rule-based checks can short-circuit before graph work.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GraphFraudRule implements FraudRule {

    private final TransactionGraphService graphService;

    private static final Map<String, FraudRuleType> SIGNAL_TO_RULE = Map.of(
        "CIRCULAR_FLOW", FraudRuleType.GRAPH_CIRCULAR_FLOW,
        "HIGH_DEGREE_NODE", FraudRuleType.GRAPH_HIGH_DEGREE_NODE,
        "BIDIRECTIONAL_HUB", FraudRuleType.GRAPH_HIGH_DEGREE_NODE,
        "SUSPICIOUS_CLUSTER", FraudRuleType.GRAPH_SUSPICIOUS_CLUSTER
    );

    @Override
    public List<FraudAlert> evaluate(Transaction transaction) {
        List<FraudAlert> alerts = new ArrayList<>();

        if (transaction.getSender() == null || transaction.getSender().getId() == null) {
            return alerts;
        }

        String userId = transaction.getSender().getId();

        List<GraphAnalysisResult> results = List.of(
            graphService.analyzeCircularFlow(userId),
            graphService.analyzeNodeDegree(userId),
            graphService.analyzeCluster(userId)
        );

        int vertices = graphService.getGraphVertexCount();
        int edges = graphService.getGraphEdgeCount();

        for (GraphAnalysisResult result : results) {
            if (result.suspicious()) {
                alerts.add(toFraudAlert(transaction, userId, result, vertices, edges));
            }
        }

        return alerts;
    }

    private FraudAlert toFraudAlert(
            Transaction transaction,
            String userId,
            GraphAnalysisResult result,
            int vertices,
            int edges) {

        FraudRuleType ruleType = SIGNAL_TO_RULE.getOrDefault(
            result.signalType(),
            FraudRuleType.GRAPH_SUSPICIOUS_CLUSTER
        );

        return FraudAlert.builder()
            .transaction(transaction)
            .userId(userId)
            .ruleType(ruleType)
            .riskLevel(toRiskLevel(result.riskScore()))
            .scoreContribution(result.riskScore())
            .description("[GRAPH] " + result.description())
            .metadata(String.format(
                "{\"signal\":\"%s\",\"vertices\":%d,\"edges\":%d}",
                result.signalType(),
                vertices,
                edges
            ))
            .build();
    }

    private RiskLevel toRiskLevel(double score) {
        if (score >= 35.0) {
            return RiskLevel.CRITICAL;
        }

        if (score >= 25.0) {
            return RiskLevel.HIGH;
        }

        return RiskLevel.MEDIUM;
    }

    @Override
    public String getRuleName() {
        return "GRAPH_ANALYSIS";
    }

    @Override
    public int getPriority() {
        return 50; // graph checks run after cheaper rules
    }
}