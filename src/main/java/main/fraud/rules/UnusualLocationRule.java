package main.fraud.rules;

import jakarta.annotation.PostConstruct;
import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure String comparison — zero I/O, runs second.
 * High-risk countries are configurable through application.yml so the list can be
 * updated without changing code.
 */
@Component
public class UnusualLocationRule implements FraudRule {

    @Value("${fraud.rules.location.high-risk-countries:KP,IR,SY,CU,SD,MM,BY,RU}")
    private String highRiskCountriesConfig;

    private Set<String> highRiskCountries;

    @PostConstruct
    void init() {
        highRiskCountries = Arrays.stream(highRiskCountriesConfig.split(","))
            .map(String::trim)
            .filter(country -> !country.isBlank())
            .map(country -> country.toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(HashSet::new));
    }

    @Override
    public List<FraudAlert> evaluate(Transaction transaction) {
        List<FraudAlert> alerts = new ArrayList<>();

        String userCountry = transaction.getSender().getHomeCountry();
        String txnCountry = transaction.getCountry();

        if (txnCountry == null || txnCountry.isBlank()) {
            return alerts;
        }

        if (userCountry == null || userCountry.isBlank()) {
            return alerts;
        }

        String normalizedTxnCountry = txnCountry.trim().toUpperCase(Locale.ROOT);
        String normalizedUserCountry = userCountry.trim().toUpperCase(Locale.ROOT);

        if (!normalizedTxnCountry.equals(normalizedUserCountry)) {
            boolean isHighRisk = highRiskCountries.contains(normalizedTxnCountry);

            alerts.add(FraudAlert.builder()
                .transaction(transaction)
                .userId(transaction.getSender().getId())
                .ruleType(isHighRisk
                    ? FraudRuleType.CROSS_BORDER_HIGH_RISK
                    : FraudRuleType.UNUSUAL_LOCATION)
                .riskLevel(isHighRisk ? RiskLevel.CRITICAL : RiskLevel.MEDIUM)
                .scoreContribution(isHighRisk ? 35.0 : 15.0)
                .description(String.format(
                    "Transaction in %s differs from user home country %s%s",
                    txnCountry,
                    userCountry,
                    isHighRisk ? " — HIGH RISK JURISDICTION" : ""
                ))
                .metadata(String.format(
                    "{\"userCountry\":\"%s\",\"txnCountry\":\"%s\",\"highRisk\":%b}",
                    userCountry,
                    txnCountry,
                    isHighRisk
                ))
                .build());
        }

        String userCity = transaction.getSender().getHomeCity();
        String txnLocation = transaction.getLocation();

        if (userCity != null && !userCity.isBlank()
                && txnLocation != null && !txnLocation.isBlank()
                && normalizedTxnCountry.equals(normalizedUserCountry)
                && !txnLocation.trim().equalsIgnoreCase(userCity.trim())) {

            alerts.add(FraudAlert.builder()
                .transaction(transaction)
                .userId(transaction.getSender().getId())
                .ruleType(FraudRuleType.UNUSUAL_LOCATION)
                .riskLevel(RiskLevel.LOW)
                .scoreContribution(5.0)
                .description(String.format(
                    "Transaction location '%s' differs from home city '%s'",
                    txnLocation,
                    userCity
                ))
                .metadata(String.format(
                    "{\"homeCity\":\"%s\",\"txnCity\":\"%s\"}",
                    userCity,
                    txnLocation
                ))
                .build());
        }

        return alerts;
    }

    @Override
    public String getRuleName() {
        return "UNUSUAL_LOCATION";
    }

    @Override
    public int getPriority() {
        return 10; // pure String comparison, zero I/O — runs after amount rule
    }
}