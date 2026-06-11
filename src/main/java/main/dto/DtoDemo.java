package main.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;
import main.dto.request.*;
import main.dto.response.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Demonstrates every request and response DTO.
 * Uses Jakarta Validator directly to show which annotations catch which bad inputs —
 * the same validator Spring runs before any controller method is called.
 */
public class DtoDemo {

    private static final Validator VALIDATOR =
        Validation.buildDefaultValidatorFactory().getValidator();

    public static void main(String[] args) {


        // REQUEST DTOs
        System.out.println("-----------------------------");
        System.out.println("  REQUEST DTOs");
        System.out.println("-----------------------------");


        // 1. RegisterRequest 
        System.out.println("\n-- RegisterRequest (valid) --");

        RegisterRequest register = new RegisterRequest();
        register.setName("Jane Smith");
        register.setEmail("jane.smith@example.com");
        register.setUsername("janesmith");
        register.setPassword("S3cur3P@ss!");
        register.setPhoneNumber("+14155552671");
        register.setInitialBalance(new BigDecimal("10000.00"));
        register.setHomeCountry("US");
        register.setHomeCity("New York");

        printDto(register);
        printViolations(VALIDATOR.validate(register));


        // 2. RegisterRequest — validation failures
        System.out.println("\n-- RegisterRequest (invalid inputs) --");

        RegisterRequest badRegister = new RegisterRequest();
        badRegister.setName(""); // @NotBlank fails
        badRegister.setEmail("not-an-email"); // @Email fails
        badRegister.setUsername("ab"); // @Size(min=3) fails
        badRegister.setPassword("short"); // @Size(min=8) fails
        badRegister.setPhoneNumber("+0123456789"); // @Pattern fails — country code starts with 0
        badRegister.setInitialBalance(new BigDecimal("-5.00")); // @DecimalMin("0.00") fails
        badRegister.setHomeCountry("X"); // @Size(min=2) fails

        printViolations(VALIDATOR.validate(badRegister));


        // 3. RegisterRequest — phone format edge cases
        System.out.println("\n-- Phone E.164 validation edge cases --");

        String[] phones = {
            "+14155552671", // valid US
            "+447911123456", // valid UK
            "+35312345678", // valid Ireland
            "14155552671", // missing +
            "+0141555", // country code starts with 0
            "+44 791 112", // spaces not allowed
            "+1", // too short
            "+1" + "1".repeat(15), // too long
        };

        for (String phone : phones) {
            RegisterRequest r = new RegisterRequest();
            r.setName("Test"); r.setEmail("t@t.com"); r.setUsername("test123");
            r.setPassword("password123"); r.setPhoneNumber(phone);
            r.setInitialBalance(BigDecimal.TEN); r.setHomeCountry("US");

            Set<ConstraintViolation<RegisterRequest>> v = VALIDATOR.validate(r);
            boolean phoneViolation = v.stream().anyMatch(cv -> cv.getPropertyPath().toString().equals("phoneNumber"));
            System.out.printf("  %-25s -> %s%n", phone, phoneViolation ? "REJECTED" : "ACCEPTED");
        }


        // 4. LoginRequest 
        System.out.println("\n-- LoginRequest --");

        LoginRequest login = new LoginRequest();
        login.setUsername("janesmith");
        login.setPassword("S3cur3P@ss!");
        login.setDeviceName("Jane's MacBook");
        login.setDeviceFingerprint("d41d8cd98f00b204");
        login.setIpAddress("192.168.1.1");

        printDto(login);
        printViolations(VALIDATOR.validate(login));

        LoginRequest badLogin = new LoginRequest();
        badLogin.setUsername(""); // @NotBlank fails
        badLogin.setPassword(""); // @NotBlank fails
        printViolations(VALIDATOR.validate(badLogin));


        // 5. PaymentRequest — merchant payment
        System.out.println("\n-- PaymentRequest (merchant payment) --");

        PaymentRequest merchantPayment = new PaymentRequest();
        merchantPayment.setAmount(new BigDecimal("250.00"));
        merchantPayment.setMerchant("Amazon");
        merchantPayment.setLocation("New York");
        merchantPayment.setCountry("US");
        merchantPayment.setIpAddress("192.168.1.1");
        merchantPayment.setDeviceFingerprint("d41d8cd98f00b204");

        printDto(merchantPayment);
        printViolations(VALIDATOR.validate(merchantPayment));


        // 6. PaymentRequest — P2P transfer
        System.out.println("\n-- PaymentRequest (P2P transfer) --");

        PaymentRequest p2pPayment = new PaymentRequest();
        p2pPayment.setAmount(new BigDecimal("500.00"));
        p2pPayment.setReceiverId("receiver-uuid-here");
        p2pPayment.setLocation("London");
        p2pPayment.setCountry("GB");

        printDto(p2pPayment);
        printViolations(VALIDATOR.validate(p2pPayment));


        // 7. PaymentRequest — validation failures
        System.out.println("\n-- PaymentRequest (invalid) --");

        PaymentRequest badPayment = new PaymentRequest();
        badPayment.setAmount(new BigDecimal("0.00")); // @DecimalMin("0.01") fails
        badPayment.setLocation(""); // @NotBlank fails
        badPayment.setMerchant("A".repeat(300)); // @Size(max=255) fails

        printViolations(VALIDATOR.validate(badPayment));


        // 8. RefreshTokenRequest
        System.out.println("\n-- RefreshTokenRequest --");

        RefreshTokenRequest refresh = new RefreshTokenRequest();
        refresh.setRefreshToken("eyJhbGciOiJIUzI1NiJ9.refresh_token_here");

        printDto(refresh);
        printViolations(VALIDATOR.validate(refresh));


        // 9. RevokeSessionRequest
        System.out.println("\n-- RevokeSessionRequest --");

        RevokeSessionRequest revoke = new RevokeSessionRequest();
        revoke.setSessionId("session-uuid-here");

        printDto(revoke);
        printViolations(VALIDATOR.validate(revoke));


        // RESPONSE DTOs
        System.out.println("-----------------------------");
        System.out.println("  RESPONSE DTOs  (built by the server, no validation)");
        System.out.println("-----------------------------");


        // 10. LoginResponse
        System.out.println("\n-- LoginResponse --");

        LoginResponse loginResponse = LoginResponse.builder()
            .accessToken("eyJhbGciOiJIUzI1NiJ9.access")
            .refreshToken("eyJhbGciOiJIUzI1NiJ9.refresh")
            .sessionId("session-uuid-abc")
            .username("janesmith")
            .tokenType("Bearer")
            .accessTokenExpiresInSeconds(900) // 15 min
            .refreshTokenExpiresInSeconds(604800) // 7 days
            .build();

        printDto(loginResponse);


        // 11. PaymentResponse
        System.out.println("\n-- PaymentResponse --");

        PaymentResponse paymentResponse = PaymentResponse.builder()
            .transactionId("txn-uuid-xyz")
            .status(TransactionStatus.APPROVED)
            .amount(new BigDecimal("250.00"))
            .merchant("Amazon")
            .fraudScore(12.5)
            .message("Payment approved")
            .submittedAt(Instant.now())
            .build();

        printDto(paymentResponse);


        // 12. TransactionResponse — with nested FraudAlertResponse list
        System.out.println("\n-- TransactionResponse (with fraud alerts) --");

        FraudAlertResponse alert = FraudAlertResponse.builder()
            .id("alert-uuid-1")
            .transactionId("txn-uuid-xyz")
            .userId("user-uuid-jane")
            .ruleType(FraudRuleType.HIGH_AMOUNT)
            .riskLevel(RiskLevel.MEDIUM)
            .scoreContribution(22.0)
            .description("Amount is 2x above user average")
            .resolved(false)
            .createdAt(Instant.now())
            .build();

        TransactionResponse txnResponse = TransactionResponse.builder()
            .id("txn-uuid-xyz")
            .userId("user-uuid-jane")
            .amount(new BigDecimal("250.00"))
            .merchant("Amazon")
            .location("New York")
            .country("US")
            .status(TransactionStatus.FLAGGED_FOR_REVIEW)
            .riskLevel(RiskLevel.MEDIUM)
            .fraudScore(22.0)
            .processingTimeMs(38L)
            .fraudAlerts(List.of(alert))
            .createdAt(Instant.now())
            .build();

        printDto(txnResponse);
        System.out.println(" fraudAlerts[0].ruleType:  " + txnResponse.getFraudAlerts().get(0).getRuleType());
        System.out.println(" fraudAlerts[0].score:     " + txnResponse.getFraudAlerts().get(0).getScoreContribution());


        // 13. FraudAlertResponse (standalone)
        System.out.println("\n-- FraudAlertResponse --");
        printDto(alert);


        // 14. UserResponse
        System.out.println("\n-- UserResponse --");

        UserResponse userResponse = UserResponse.builder()
            .id("user-uuid-jane")
            .name("Jane Smith")
            .email("jane.smith@example.com")
            .phoneNumber("+1415***2671") // masked before returning to client
            .emailVerified(true)
            .phoneVerified(true)
            .balance(new BigDecimal("9750.00"))
            .homeCountry("US")
            .homeCity("New York")
            .riskScore(5.2)
            .active(true)
            .flagged(false)
            .createdAt(Instant.now())
            .build();

        printDto(userResponse);


        // 15. SessionResponse — "your active devices" screen
        System.out.println("\n-- SessionResponse --");

        SessionResponse session = SessionResponse.builder()
            .sessionId("session-uuid-abc")
            .deviceName("Jane's MacBook")
            .deviceFingerprint("d41d8cd98f00b204")
            .ipAddressIssued("192.168.1.1")
            .lastSeenIp("192.168.1.1")
            .lastSeenAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(604800))
            .active(true)
            .createdAt(Instant.now())
            .build();

        printDto(session);


        // 16. DashboardStatsResponse
        System.out.println("\n-- DashboardStatsResponse --");

        DashboardStatsResponse dashboard = DashboardStatsResponse.builder()
            .totalTransactions(15420L)
            .approvedTransactions(14100L)
            .declinedTransactions(820L)
            .fraudBlockedTransactions(390L)
            .flaggedForReview(110L)
            .totalVolume(new BigDecimal("4823500.00"))
            .fraudPreventedAmount(new BigDecimal("193000.00"))
            .averageFraudScore(8.4)
            .queueDepth(110)
            .activeWorkers(4L)
            .alertsByRuleType(Map.of(
                "HIGH_AMOUNT", 214L,
                "VELOCITY_BREACH", 98L,
                "BLACKLISTED_MERCHANT", 44L))
            .transactionsByStatus(Map.of(
                "APPROVED", 14100L,
                "DECLINED", 820L,
                "FRAUD_BLOCKED", 390L,
                "FLAGGED_FOR_REVIEW", 110L))
            .build();

        printDto(dashboard);


        // 17. ApiErrorResponse — what the client gets on any 4xx/5xx
        System.out.println("\n-- ApiErrorResponse --");

        ApiErrorResponse error400 = ApiErrorResponse.builder()
            .status(400)
            .error("Bad Request")
            .message("Validation failed")
            .path("/auth/register")
            .timestamp(Instant.now())
            .details(List.of(
                "phoneNumber: Phone number must be in E.164 format, e.g. +14155552671",
                "password: size must be between 8 and 100"))
            .build();

        printDto(error400);

        ApiErrorResponse error401 = ApiErrorResponse.builder()
            .status(401)
            .error("Unauthorized")
            .message("JWT token is expired or invalid")
            .path("/payments")
            .timestamp(Instant.now())
            .build();

        printDto(error401);
    }


    // Helpers
    private static void printDto(Object dto) {
        System.out.println("  " + dto);
    }

    private static <T> void printViolations(Set<ConstraintViolation<T>> violations) {
        if (violations.isEmpty()) System.out.println(" Validation: PASS (0 violations)");
        else {
            System.out.println(" Validation: FAIL (" + violations.size() + " violation(s))");
            violations.forEach(v ->
                System.out.println("    [" + v.getPropertyPath() + "] " + v.getMessage()
                    + " - got: '" + v.getInvalidValue() + "'"));
        }
    }
}