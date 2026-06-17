package main.exception;

import java.math.BigDecimal;

public class InsufficientBalanceException extends RuntimeException {
    
    public InsufficientBalanceException(String userId, BigDecimal required, BigDecimal available) {
        super(String.format("User %s has insufficient balance: required %.2f, available %.2f", 
                userId, required, available));
    }
}
