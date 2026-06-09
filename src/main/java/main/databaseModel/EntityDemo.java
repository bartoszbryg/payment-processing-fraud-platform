package main.databaseModel;

import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;

import java.lang.reflect.Method;
import java.math.BigDecimal;

public class EntityDemo {

    public static void main(String[] args) throws Exception {

        
        // 1. USER — builder defaults and field values
        System.out.println("=== USER ===");

        User user = User.builder()
            .name("Alice Smith")
            .email("alice@example.com")
            .balance(new BigDecimal("1500.00"))
            .homeCountry("US")
            .homeCity("New York")
            .build();

        System.out.println("Name:        " + user.getName());
        System.out.println("Email:       " + user.getEmail());
        System.out.println("Balance:     " + user.getBalance());
        System.out.println("Country:     " + user.getHomeCountry());
        System.out.println("Risk Score:  " + user.getRiskScore() + " (default 0.0)");
        System.out.println("Active:      " + user.isActive() + " (default true)");
        System.out.println("Flagged:     " + user.isFlagged() + " (default false)");
        System.out.println("Version:     " + user.getVersion() + " (null until first save)");
        System.out.println("Sent txns:   " + user.getSentTransactions().size() + " (empty list by default)");
        System.out.println("Recv txns:   " + user.getReceivedTransactions().size() + " (empty list by default)");

       
        // 2. APP USER — separate from payment user
        System.out.println("\n=== APP USER (system login, not a customer) ===");

        AppUser admin = AppUser.builder()
            .username("admin")
            .password("hashed_secret")
            .roles(java.util.Set.of("ROLE_ADMIN", "ROLE_ANALYST"))
            .build();

        System.out.println("Username:    " + admin.getUsername());
        System.out.println("Roles:       " + admin.getRoles());
        System.out.println("Active:      " + admin.isActive() + " (default true)");
        System.out.println("LastLogin:   " + admin.getLastLoginAt() + " (null until first login)");

      
        // 3. MERCHANT TRANSACTION — receiver must be null
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
        System.out.println("Status:      " + merchantTxn.getStatus() + " (default PENDING)");
        System.out.println("Risk Level:  " + merchantTxn.getRiskLevel() + " (default LOW)");
        System.out.println("Fraud Score: " + merchantTxn.getFraudScore() + " (default 0.0)");
        System.out.println("isP2P():     " + merchantTxn.isP2P() + " (false - has merchant)");


        // 4. P2P TRANSACTION — merchant must be null
        System.out.println("\n=== TRANSACTION — P2P transfer ===");

        User receiver = User.builder()
            .name("Bob Jones")
            .email("bob@example.com")
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
        System.out.println("isP2P():     " + p2pTxn.isP2P() + " (true — has receiver)");


        // 5. FRAUD ALERT — references a transaction
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


        // 6. MERCHANT BLACKLIST
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


        // 7. @PrePersist GUARD — validateMerchantOrReceiver
        //    Private method; invoked via reflection to simulate what JPA does
        System.out.println("\n=== @PrePersist VALIDATION ===");

        testValidation("Both merchant AND receiver set (invalid)", () -> {
            Transaction bad = Transaction.builder()
                .sender(user)
                .merchant("Amazon")
                .receiver(receiver)
                .amount(new BigDecimal("10.00"))
                .location("NY")
                .build();
            invokeValidate(bad);
        });

        testValidation("Neither merchant NOR receiver set (invalid)", () -> {
            Transaction bad = Transaction.builder()
                .sender(user)
                .amount(new BigDecimal("10.00"))
                .location("NY")
                .build();
            invokeValidate(bad);
        });

        testValidation("Only merchant set (valid)", () -> {
            Transaction ok = Transaction.builder()
                .sender(user)
                .merchant("Amazon")
                .amount(new BigDecimal("10.00"))
                .location("NY")
                .build();
            invokeValidate(ok);
        });

        testValidation("Only receiver set (valid)", () -> {
            Transaction ok = Transaction.builder()
                .sender(user)
                .receiver(receiver)
                .amount(new BigDecimal("10.00"))
                .location("NY")
                .build();
            invokeValidate(ok);
        });


        // 8. RISK LEVEL integration — same enum used by both Transaction and FraudAlert
        System.out.println("\n=== RISK LEVEL INTEGRATION ===");

        double[] scores = { 15, 45, 72, 91 };

        for (double score : scores) {
            RiskLevel level = RiskLevel.fromScore(score);
            TransactionStatus status = switch (level) {
                case CRITICAL -> TransactionStatus.FRAUD_BLOCKED;
                case HIGH -> TransactionStatus.FLAGGED_FOR_REVIEW;
                default -> TransactionStatus.APPROVED;
            };
            System.out.printf("Score %-5.1f -> %-8s -> %s%n", score, level, status);
        }
    }

    // Helpers
    // Calls the private @PrePersist method via reflection (mirrors what JPA does).
    private static void invokeValidate(Transaction txn) throws Exception {
        Method m = Transaction.class.getDeclaredMethod("validateMerchantOrReceiver");
        m.setAccessible(true);
        try {
            m.invoke(txn);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Unwrap: reflection wraps the real exception in InvocationTargetException
            Throwable cause = e.getCause();
            if (cause instanceof IllegalStateException ise) throw ise;
            throw e;
        }
    }

    // Runs a test case and prints PASS/FAIL with the outcome.
    private static void testValidation(String label, ThrowingRunnable test) {
        System.out.print(label + ": ");
        try {
            test.run();
            System.out.println("PASS (no exception)");
        } catch (IllegalStateException e) {
            System.out.println("PASS (blocked - " + e.getMessage() + ")");
        } catch (Exception e) {
            System.out.println("UNEXPECTED ERROR - " + e.getMessage());
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}