package main.application.states;

public enum RiskLevel {
    LOW(0, 30),
    MEDIUM(31, 60),
    HIGH(61, 85),
    CRITICAL(86, 100);

    private final int minScore;
    private final int maxScore;

    RiskLevel(int minScore, int maxScore) {
        this.minScore = minScore;
        this.maxScore = maxScore;
    }

    public static RiskLevel fromScore(double score) {
        int s = (int) score;

        if (s <= LOW.maxScore) return LOW;
        if (s <= MEDIUM.maxScore) return MEDIUM;
        if (s <= HIGH.maxScore) return HIGH;

        return CRITICAL;
    }

    public int getMinScore() { 
        return minScore; 
    }

    public int getMaxScore() { 
        return maxScore; 
    }
}