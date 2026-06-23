package main.fraud.rules;

import java.util.List;

import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;


/**
 * Strategy interface for all fraud detection rules.
 * Each implementation evaluates one fraud signal and returns any alerts produced by that rule.
 */
public interface FraudRule {

    /** Evaluate the transaction; return generated alerts (empty list = rule did not trigger). */
    List<FraudAlert> evaluate(Transaction transaction);

    /** Human-readable name used in logs and Micrometer metrics tags. */
    String getRuleName();

    /** Execution order — lower number runs first. Cheap/blocking rules should run before graph rules. */
    int getPriority();
}
