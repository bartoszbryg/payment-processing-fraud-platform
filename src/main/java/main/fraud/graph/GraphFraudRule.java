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
import java.util.LinkedHashMap;
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
        String receiverId = transaction.getReceiver() != null ? transaction.getReceiver().getId() : null;
        boolean isP2P = receiverId != null;

        // The sender -> receiver edge for this transaction doesn't exist in the graph yet —
        // addTransaction() only runs after the verdict — so the plain analyze* methods, which
        // only look at edges already there, can't see a signal this specific transaction would
        // itself create. For P2P, use the "would this edge..." variants instead of (not in
        // addition to) the plain check: wouldExceedDegreeThreshold returns the same verdict
        // analyzeNodeDegree would once this edge lands, so there's no double-counting when the
        // threshold was already crossed before this transaction. Merchant payments can't close
        // a user->user cycle or add a new user counterparty, so they keep the plain checks.
        GraphAnalysisResult nodeDegreeResult = isP2P
            ? graphService.wouldExceedDegreeThreshold(userId, receiverId)
            : graphService.analyzeNodeDegree(userId);

        List<GraphAnalysisResult> results = new ArrayList<>(List.of(
            graphService.analyzeCircularFlow(userId),
            nodeDegreeResult,
            graphService.analyzeCluster(userId)
        ));

        if (isP2P) {
            results.add(graphService.wouldCompleteCycle(userId, receiverId));
        }

        int vertices = graphService.getGraphVertexCount();
        int edges = graphService.getGraphEdgeCount();

        // Two different signals can describe the same underlying problem - e.g.
        // analyzeCircularFlow(sender) and wouldCompleteCycle(sender, receiver) both noticing
        // the same ring from different starting points. Collapsing to the single
        // highest-scoring result per rule type stops a coincidental double-detection from
        // doubling the transaction's score, while genuinely different rule types (circular
        // flow vs. degree threshold) still each produce their own alert.
        Map<FraudRuleType, GraphAnalysisResult> bestByRule = new LinkedHashMap<>();
        for (GraphAnalysisResult result : results) {
            if (!result.suspicious()) {
                continue;
            }

            FraudRuleType ruleType = toRuleType(result);
            GraphAnalysisResult current = bestByRule.get(ruleType);
            if (current == null || result.riskScore() > current.riskScore()) {
                bestByRule.put(ruleType, result);
            }
        }

        for (Map.Entry<FraudRuleType, GraphAnalysisResult> entry : bestByRule.entrySet()) {
            alerts.add(toFraudAlert(transaction, userId, entry.getKey(), entry.getValue(), vertices, edges));
        }

        return alerts;
    }

    private FraudRuleType toRuleType(GraphAnalysisResult result) {
        return SIGNAL_TO_RULE.getOrDefault(result.signalType(), FraudRuleType.GRAPH_SUSPICIOUS_CLUSTER);
    }

    private FraudAlert toFraudAlert(
            Transaction transaction,
            String userId,
            FraudRuleType ruleType,
            GraphAnalysisResult result,
            int vertices,
            int edges) {

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