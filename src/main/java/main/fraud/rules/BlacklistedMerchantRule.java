package main.fraud.rules;

import lombok.RequiredArgsConstructor;
import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.fraud.cache.MerchantBlacklistCache;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Blocks transactions to merchants on the active blacklist.
 * Uses MerchantBlacklistCache as a separate bean so the @Cacheable proxy is honored.
 * Calling a @Cacheable method from inside the same class would bypass Spring AOP.
 */
@Component
@RequiredArgsConstructor
public class BlacklistedMerchantRule implements FraudRule {

    private final MerchantBlacklistCache blacklistCache;

    @Override
    public List<FraudAlert> evaluate(Transaction transaction) {
        List<FraudAlert> alerts = new ArrayList<>();

        // P2P transfer — no merchant to check.
        if (transaction.getMerchant() == null) return alerts;
        

        // Cache hit: no DB call. Cold miss: one indexed lookup by merchant_name.
        blacklistCache.findActive(transaction.getMerchant()).ifPresent(entry ->
            alerts.add(FraudAlert.builder()
                .transaction(transaction)
                .userId(transaction.getSender().getId())
                .ruleType(FraudRuleType.BLACKLISTED_MERCHANT)
                .riskLevel(RiskLevel.CRITICAL)
                .scoreContribution(100.0)
                .description(String.format("Merchant '%s' is blacklisted: %s",
                    transaction.getMerchant(), entry.getReason()))
                .metadata(String.format("{\"merchant\":\"%s\",\"reason\":\"%s\"}",
                    transaction.getMerchant(), entry.getReason()))
                .build())
        );

        return alerts;
    }

    @Override
    public String getRuleName() {
        return "BLACKLISTED_MERCHANT";
    }

    @Override
    public int getPriority() {
        return 15; // after pure-arithmetic rules, before DB-count rules
    }
}