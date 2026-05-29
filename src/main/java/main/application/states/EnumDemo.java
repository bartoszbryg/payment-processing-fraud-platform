package main.application.states;

public class EnumDemo {

    public static void main(String[] args) {

        System.out.println("-- Transaction Status --");

        for (TransactionStatus status : TransactionStatus.values()) {
            System.out.println(status);
        }

        System.out.println();

        System.out.println("Fraud Rule Type:");

        for (FraudRuleType rule : FraudRuleType.values()) {
            System.out.println(rule);
        }

        System.out.println();

        System.out.println("-- Risk Level Ranges --");

        for (RiskLevel level : RiskLevel.values()) {
            System.out.println(level + ": Min = " + level.getMinScore() +
                " | Max = " + level.getMaxScore());
        }

        System.out.println();

        System.out.println("-- RiskLevel.fromScore() --");

        double[] scores = {
            0, 10, 30,
            31, 45, 60,
            61, 70, 85,
            86, 92, 100
        };

        for (double score : scores) {
            System.out.println(score + " -> " + RiskLevel.fromScore(score));
        }

        System.out.println();

        System.out.println("-- Example Fraud Decision --");

        double riskScore = 92;

        RiskLevel level = RiskLevel.fromScore(riskScore);

        TransactionStatus status;

        if (level == RiskLevel.CRITICAL) {
            status = TransactionStatus.FRAUD_BLOCKED;
        } else if (level == RiskLevel.HIGH) {
            status = TransactionStatus.FLAGGED_FOR_REVIEW;
        } else {
            status = TransactionStatus.APPROVED;
        }

        System.out.println("Score: " + riskScore);
        System.out.println("Risk Level: " + level);
        System.out.println("Final Status: " + status);
    }
}