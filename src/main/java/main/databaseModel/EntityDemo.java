package main.databaseModel;

import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public class EntityDemo {

    public static void main(String[] args) throws Exception {


        // 1. USER — includes phoneNumber (required, E.164), emailVerified, phoneVerified
        System.out.println("=== USER ===");

        User user = User.builder()
            .name("Alice Smith")
            .email("alice@example.com")
            .phoneNumber("+14155552671")
            .balance(new BigDecimal("1500.00"))
            .homeCountry("US")
            .homeCity("New York")
            .build();

        System.out.println("Name:           " + user.getName());
        System.out.println("Email:          " + user.getEmail());
        System.out.println("Phone:          " + user.getPhoneNumber());
        System.out.println("Balance:        " + user.getBalance());
        System.out.println("Country:        " + user.getHomeCountry());
        System.out.println("Risk Score:     " + user.getRiskScore()     + " (default 0.0)");
        System.out.println("Active:         " + user.isActive()         + " (default true)");
        System.out.println("Flagged:        " + user.isFlagged()        + " (default false)");
        System.out.println("emailVerified:  " + user.isEmailVerified()  + " (default false — set after email OTP)");
        System.out.println("phoneVerified:  " + user.isPhoneVerified()  + " (default false — set after SMS OTP)");
        System.out.println("Version:        " + user.getVersion()       + " (null until first DB save)");
        System.out.println("Sent txns:      " + user.getSentTransactions().size()     + " (empty list by default)");
        System.out.println("Recv txns:      " + user.getReceivedTransactions().size() + " (empty list by default)");


        // 2. APP USER — email required, brute-force fields, linkedUserId for customers
        System.out.println("\n=== APP USER (system login — admin has no linked bank account) ===");

        AppUser admin = AppUser.builder()
            .username("admin")
            .email("admin@bank.internal")
            .password("hashed_secret")
            .roles(Set.of("ROLE_ADMIN"))
            .build();

        System.out.println("Username:            " + admin.getUsername());
        System.out.println("Email:               " + admin.getEmail());
        System.out.println("Roles:               " + admin.getRoles());
        System.out.println("Active:              " + admin.isActive()               + " (default true)");
        System.out.println("FailedAttempts:      " + admin.getFailedLoginAttempts() + " (default 0)");
        System.out.println("LockedUntil:         " + admin.getLockedUntil()         + " (null until threshold hit)");
        System.out.println("LinkedUserId:        " + admin.getLinkedUserId()        + " (null for admins/analysts)");
        System.out.println("LastLogin:           " + admin.getLastLoginAt()         + " (null until first login)");

        System.out.println("\n=== APP USER (customer login — linkedUserId bridges to the bank account) ===");

        AppUser customerLogin = AppUser.builder()
            .username("alice_login")
            .email("alice@example.com")
            .password("hashed_password")
            .roles(Set.of("ROLE_USER"))
            .linkedUserId("alice-uuid-from-users-table")
            .build();

        System.out.println("Username:      " + customerLogin.getUsername());
        System.out.println("Roles:         " + customerLogin.getRoles());
        System.out.println("LinkedUserId:  " + customerLogin.getLinkedUserId() + " (points to User.id)");


        // 3. USER SESSION — one row per logged-in device
        System.out.println("\n=== USER SESSION (one row per device — enables remote revocation) ===");

        UserSession session = UserSession.builder()
            .appUserId("alice-app-user-uuid")
            .refreshTokenHash("$2a$10$hashed_refresh_token_here")
            .deviceFingerprint("d41d8cd98f00b204e9800998ecf8427e")
            .deviceName("Alice's iPhone 14")
            .ipAddressIssued("192.168.1.1")
            .lastSeenIp("192.168.1.1")
            .lastSeenAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60 * 60 * 24 * 7))
            .build();

        System.out.println("AppUserId:         " + session.getAppUserId());
        System.out.println("DeviceName:        " + session.getDeviceName());
        System.out.println("DeviceFingerprint: " + session.getDeviceFingerprint());
        System.out.println("IPIssued:          " + session.getIpAddressIssued());
        System.out.println("LastSeenIP:        " + session.getLastSeenIp());
        System.out.println("ExpiresAt:         " + session.getExpiresAt());
        System.out.println("Active:            " + session.isActive() + " (default true)");
        System.out.println("RefreshHash:       " + session.getRefreshTokenHash().substring(0, 20) + "... (stored hash, never raw token)");


        // 4. MERCHANT TRANSACTION
        System.out.println("\n=== TRANSACTION — merchant payment ===");

        Transaction merchantTxn = Transaction.builder()
            .sender(user)
            .merchant("Amazon")
            .amount(new BigDecimal("49.99"))
            .location("New York, US")
            .country("US")
            .build();

        System.out.println("Merchant:    " + merchantTxn.getMerchant());
        System.out.println("Amount:      " + merchantTxn.getAmount());
        System.out.println("Status:      " + merchantTxn.getStatus()     + " (default PENDING)");
        System.out.println("Risk Level:  " + merchantTxn.getRiskLevel()  + " (default LOW)");
        System.out.println("Fraud Score: " + merchantTxn.getFraudScore() + " (default 0.0)");
        System.out.println("isP2P():     " + merchantTxn.isP2P()         + " (false — has merchant)");


        // 5. P2P TRANSACTION
        System.out.println("\n=== TRANSACTION — P2P transfer ===");

        User receiver = User.builder()
            .name("Bob Jones")
            .email("bob@example.com")
            .phoneNumber("+14155559999")
            .balance(new BigDecimal("800.00"))
            .homeCountry("US")
            .build();

        Transaction p2pTxn = Transaction.builder()
            .sender(user)
            .receiver(receiver)
            .amount(new BigDecimal("200.00"))
            .location("New York, US")
            .country("US")
            .build();

        System.out.println("Sender:      " + p2pTxn.getSender().getName());
        System.out.println("Receiver:    " + p2pTxn.getReceiver().getName());
        System.out.println("Merchant:    " + p2pTxn.getMerchant() + " (null for P2P)");
        System.out.println("isP2P():     " + p2pTxn.isP2P()       + " (true — has receiver)");


        // 6. FRAUD ALERT
        System.out.println("\n=== FRAUD ALERT ===");

        FraudAlert alert = FraudAlert.builder()
            .transaction(merchantTxn)
            .userId("alice-uuid-123")
            .ruleType(FraudRuleType.HIGH_AMOUNT)
            .riskLevel(RiskLevel.HIGH)
            .scoreContribution(35.0)
            .description("Transaction amount exceeds user average by 3x")
            .metadata("{\"threshold\": 50, \"userAvg\": 16.5}")
            .build();

        System.out.println("Rule:        " + alert.getRuleType());
        System.out.println("Risk Level:  " + alert.getRiskLevel());
        System.out.println("Score Cont:  " + alert.getScoreContribution());
        System.out.println("Resolved:    " + alert.isResolved() + " (default false)");
        System.out.println("Metadata:    " + alert.getMetadata());


        // 7. MERCHANT BLACKLIST
        System.out.println("\n=== MERCHANT BLACKLIST ===");

        MerchantBlacklist blocked = MerchantBlacklist.builder()
            .merchantName("ShadyStore")
            .reason("Associated with multiple chargebacks")
            .addedBy("analyst_01")
            .build();

        System.out.println("Merchant:    " + blocked.getMerchantName());
        System.out.println("Reason:      " + blocked.getReason());
        System.out.println("Added by:    " + blocked.getAddedBy());
        System.out.println("Active:      " + blocked.isActive() + " (default true — soft delete)");


        // 8. @PrePersist GUARD — validateMerchantOrReceiver
        System.out.println("\n=== @PrePersist VALIDATION ===");

        testValidation("Both merchant AND receiver set (invalid)", () -> {
            Transaction bad = Transaction.builder()
                .sender(user).merchant("Amazon").receiver(receiver)
                .amount(new BigDecimal("10.00")).location("NY").build();
            invokeValidate(bad);
        });

        testValidation("Neither merchant NOR receiver set (invalid)", () -> {
            Transaction bad = Transaction.builder()
                .sender(user).amount(new BigDecimal("10.00")).location("NY").build();
            invokeValidate(bad);
        });

        testValidation("Only merchant set (valid)", () -> {
            Transaction ok = Transaction.builder()
                .sender(user).merchant("Amazon")
                .amount(new BigDecimal("10.00")).location("NY").build();
            invokeValidate(ok);
        });

        testValidation("Only receiver set (valid)", () -> {
            Transaction ok = Transaction.builder()
                .sender(user).receiver(receiver)
                .amount(new BigDecimal("10.00")).location("NY").build();
            invokeValidate(ok);
        });


        // 9. RISK LEVEL integration
        System.out.println("\n=== RISK LEVEL INTEGRATION ===");

        double[] scores = { 15, 45, 72, 91 };
        for (double score : scores) {
            RiskLevel level = RiskLevel.fromScore(score);
            TransactionStatus status = switch (level) {
                case CRITICAL -> TransactionStatus.FRAUD_BLOCKED;
                case HIGH     -> TransactionStatus.FLAGGED_FOR_REVIEW;
                default       -> TransactionStatus.APPROVED;
            };
            System.out.printf("Score %-5.1f -> %-8s -> %s%n", score, level, status);
        }


        // 10. EMAIL + PHONE VERIFICATION FLAG LIFECYCLE
        System.out.println("\n=== VERIFICATION FLAG LIFECYCLE ===");

        User unverified = User.builder()
            .name("Charlie New")
            .email("charlie@example.com")
            .phoneNumber("+442071234567")
            .balance(BigDecimal.ZERO)
            .homeCountry("GB")
            .build();

        System.out.println("After registration:  emailVerified=" + unverified.isEmailVerified()
            + "  phoneVerified=" + unverified.isPhoneVerified()
            + "  -> payments BLOCKED");

        unverified.setEmailVerified(true);
        System.out.println("After email OTP:     emailVerified=" + unverified.isEmailVerified()
            + "  phoneVerified=" + unverified.isPhoneVerified()
            + "  -> still BLOCKED (phone unverified)");

        unverified.setPhoneVerified(true);
        System.out.println("After SMS OTP:       emailVerified=" + unverified.isEmailVerified()
            + "  phoneVerified=" + unverified.isPhoneVerified()
            + "  -> payments ALLOWED");
    }


    // Helpers

    private static void invokeValidate(Transaction txn) throws Exception {
        Method m = Transaction.class.getDeclaredMethod("validateMerchantOrReceiver");
        m.setAccessible(true);
        try {
            m.invoke(txn);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalStateException ise) throw ise;
            throw e;
        }
    }

    private static void testValidation(String label, ThrowingRunnable test) {
        System.out.print(label + ": ");
        try {
            test.run();
            System.out.println("PASS (no exception)");
        } catch (IllegalStateException e) {
            System.out.println("PASS (blocked — " + e.getMessage() + ")");
        } catch (Exception e) {
            System.out.println("UNEXPECTED ERROR — " + e.getMessage());
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}