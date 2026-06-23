package main.fraud.graph;

/**
 * Value object returned by each graph analysis method.
 * Use the static factory methods instead of constructing results manually.
 */
public record GraphAnalysisResult(
    boolean suspicious,
    String signalType,
    String description,
    double riskScore
) {

    public static GraphAnalysisResult clean() {
        return new GraphAnalysisResult(false, null, null, 0.0);
    }

    public static GraphAnalysisResult suspicious(String signalType, String description, double riskScore) {
        return new GraphAnalysisResult(true, signalType, description, riskScore);
    }
}