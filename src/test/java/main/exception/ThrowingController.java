package main.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

import main.queue.TransactionQueue.TransactionQueueFullException;

@RestController
@RequestMapping("/test")
@Validated
class ThrowingController {

    @GetMapping("/not-found/user/{id}")
    void notFoundUser(@PathVariable String id) {
        throw ResourceNotFoundException.forUser(id);
    }

    @GetMapping("/not-found/transaction/{id}")
    void notFoundTransaction(@PathVariable String id) {
        throw ResourceNotFoundException.forTransaction(id);
    }

    @GetMapping("/not-found/alert/{id}")
    void notFoundAlert(@PathVariable String id) {
        throw ResourceNotFoundException.forAlert(id);
    }

    @GetMapping("/insufficient-balance")
    void insufficientBalance() {
        throw new InsufficientBalanceException("user-42",
                new BigDecimal("500.00"), new BigDecimal("200.00"));
    }

    @GetMapping("/queue-full")
    void queueFull() {
        throw new TransactionQueueFullException();
    }

    @PostMapping("/validation")
    void validation(@Valid @RequestBody ValidBody body) {}

    @GetMapping("/bad-credentials")
    void badCredentials() {
        throw new BadCredentialsException("bad creds");
    }

    @GetMapping("/access-denied")
    void accessDenied() {
        throw new AccessDeniedException("denied");
    }

    @GetMapping("/constraint-violation")
    void constraintViolation(@RequestParam @Min(value = 1, message = "qty must be at least 1") int qty) {}

    @GetMapping("/general-error")
    void generalError() {
        throw new RuntimeException("unexpected boom");
    }

    record ValidBody(@NotBlank(message = "name must not be blank") String name) {}
}