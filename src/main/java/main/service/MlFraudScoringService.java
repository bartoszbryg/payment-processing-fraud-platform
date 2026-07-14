package main.service;

import lombok.extern.slf4j.Slf4j;
import main.databaseModel.Transaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/*
    Calls the Python ML scoring microservice to get a fraud probability for a transaction.
    Returns Optional.empty() when the service is disabled or unreachable so the fraud engine
    continues with rule-based scoring only — the ML signal is additive, never a hard dependency.
*/
@Service
@Slf4j
public class MlFraudScoringService {

    private final RestTemplate restTemplate;

    @Value("${ml.service.url:http://localhost:8000}")
    private String mlServiceUrl;

    @Value("${ml.service.enabled:true}")
    private boolean enabled;

    public MlFraudScoringService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Optional<MlScoreResult> score(Transaction transaction) {
        if (!enabled) {
            log.debug("ML scoring disabled — skipping txn {}", transaction.getId());
            return Optional.empty();
        }

        try {
            MlScoreRequest request = buildRequest(transaction);
            MlScoreResponse response = restTemplate.postForObject(
                mlServiceUrl + "/score", request, MlScoreResponse.class);

            if (response == null) {
                log.warn("ML service returned null for txn {}", transaction.getId());
                return Optional.empty();
            }

            log.debug("ML score for txn {}: probability={} band={}",
                transaction.getId(), response.fraudProbability(), response.riskBand());

            return Optional.of(new MlScoreResult(response.fraudProbability(), response.riskBand()));

        } catch (RestClientException e) {
            log.warn("ML service unreachable for txn {}: {}", transaction.getId(), e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected ML service error for txn {}: {}", transaction.getId(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    private MlScoreRequest buildRequest(Transaction transaction) {
        ZonedDateTime createdAt = (transaction.getCreatedAt() != null
            ? transaction.getCreatedAt()
            : java.time.Instant.now()).atZone(ZoneOffset.UTC);

        int hour = createdAt.getHour();
        int dow  = createdAt.getDayOfWeek().getValue() - 1; // 0=Mon, 6=Sun
        boolean isWeekend = dow >= 5;

        double balance = transaction.getSender().getBalance().doubleValue();
        String homeCountry = transaction.getSender().getHomeCountry();
        double userRiskScore = transaction.getSender().getRiskScore();
        boolean userFlagged = transaction.getSender().isFlagged();

        return new MlScoreRequest(
            transaction.getId(),
            transaction.getAmount().doubleValue(),
            transaction.getMerchant(),
            transaction.getLocation(),
            transaction.getCountry(),
            balance,
            homeCountry,
            userRiskScore,
            userFlagged,
            hour,
            dow,
            isWeekend
        );
    }

    // -- Inner records — used only for HTTP communication with the Python service --

    public record MlScoreRequest(
        String transactionId,
        double amount,
        String merchant,
        String location,
        String country,
        double userBalance,
        String userHomeCountry,
        double userRiskScore,
        boolean userIsFlagged,
        int hourOfDay,
        int dayOfWeek,
        boolean isWeekend
    ) {}

    public record MlScoreResponse(
        String transactionId,
        double fraudProbability,
        String riskBand,
        String modelVersion,
        List<String> featuresUsed
    ) {}

    public record MlScoreResult(double fraudProbability, String riskBand) {}
}