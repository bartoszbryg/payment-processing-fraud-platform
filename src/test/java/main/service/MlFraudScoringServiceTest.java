package main.service;

import main.databaseModel.Transaction;
import main.databaseModel.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MlFraudScoringServiceTest {

    @Mock RestTemplate restTemplate;

    MlFraudScoringService mlService;

    @BeforeEach
    void setup() {
        mlService = new MlFraudScoringService(restTemplate);
        ReflectionTestUtils.setField(mlService, "mlServiceUrl", "http://ml-service:8000");
        ReflectionTestUtils.setField(mlService, "enabled", true);
    }

    private Transaction transaction(String id) {
        User sender = User.builder()
            .name("Alice").email("alice@example.com")
            .phoneNumber("+14155550001")
            .balance(new BigDecimal("5000.00"))
            .homeCountry("US").homeCity("New York")
            .build();
        sender.setId("user-001");

        Transaction t = Transaction.builder()
            .sender(sender)
            .amount(new BigDecimal("200.00"))
            .merchant("Amazon")
            .location("New York")
            .country("US")
            .build();
        t.setId(id);
        return t;
    }

    private MlFraudScoringService.MlScoreResponse scoreResponse(double probability) {
        return new MlFraudScoringService.MlScoreResponse(
            "txn-001", probability, "HIGH", "v1.0", java.util.List.of("amount", "location"));
    }

    // disabled

    @Nested
    class DisabledTests {

        @Test
        void disabled_returnsEmpty_withoutCallingRestTemplate() {
            ReflectionTestUtils.setField(mlService, "enabled", false);

            Optional<MlFraudScoringService.MlScoreResult> result = mlService.score(transaction("txn-001"));

            assertTrue(result.isEmpty());
            verifyNoInteractions(restTemplate);
        }
    }

    // success

    @Nested
    class SuccessTests {

        @Test
        void score_success_returnsMlScoreResult() {
            when(restTemplate.postForObject(anyString(), any(), eq(MlFraudScoringService.MlScoreResponse.class)))
                .thenReturn(scoreResponse(0.85));

            Optional<MlFraudScoringService.MlScoreResult> result = mlService.score(transaction("txn-001"));

            assertTrue(result.isPresent());
            assertEquals(0.85, result.get().fraudProbability(), 0.001);
            assertEquals("HIGH", result.get().riskBand());
        }

        @Test
        void score_callsCorrectUrl() {
            when(restTemplate.postForObject(anyString(), any(), any()))
                .thenReturn(scoreResponse(0.5));

            mlService.score(transaction("txn-001"));

            verify(restTemplate).postForObject(
                eq("http://ml-service:8000/score"), any(), eq(MlFraudScoringService.MlScoreResponse.class));
        }

        @Test
        void score_lowProbability_returned() {
            when(restTemplate.postForObject(anyString(), any(), any()))
                .thenReturn(scoreResponse(0.05));

            Optional<MlFraudScoringService.MlScoreResult> result = mlService.score(transaction("txn-001"));

            assertTrue(result.isPresent());
            assertEquals(0.05, result.get().fraudProbability(), 0.001);
        }
    }

    // null response

    @Nested
    class NullResponseTests {

        @Test
        void nullResponse_returnsEmpty() {
            when(restTemplate.postForObject(anyString(), any(), any())).thenReturn(null);

            Optional<MlFraudScoringService.MlScoreResult> result = mlService.score(transaction("txn-001"));

            assertTrue(result.isEmpty());
        }
    }

    // error handling

    @Nested
    class ErrorHandlingTests {

        @Test
        void restClientException_returnsEmpty_doesNotThrow() {
            when(restTemplate.postForObject(anyString(), any(), any()))
                .thenThrow(new ResourceAccessException("Connection refused"));

            Optional<MlFraudScoringService.MlScoreResult> result = mlService.score(transaction("txn-001"));

            assertTrue(result.isEmpty());
        }

        @Test
        void genericException_returnsEmpty_doesNotThrow() {
            when(restTemplate.postForObject(anyString(), any(), any()))
                .thenThrow(new RuntimeException("Unexpected error"));

            Optional<MlFraudScoringService.MlScoreResult> result = mlService.score(transaction("txn-001"));

            assertTrue(result.isEmpty());
        }
    }
}