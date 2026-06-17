package main.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import main.queue.TransactionQueue.TransactionQueueFullException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ThrowingController.class)
@AutoConfigureMockMvc(addFilters = false)
class ExceptionHandlingTest {

    @Autowired MockMvc mvc;

    // ResourceNotFoundException - 404

    @Test
    void userNotFound_returns404() throws Exception {
        mvc.perform(get("/test/not-found/user/abc123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("User not found: abc123"))
                .andExpect(jsonPath("$.path").value("/test/not-found/user/abc123"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void transactionNotFound_returns404() throws Exception {
        mvc.perform(get("/test/not-found/transaction/txn-99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Transaction not found: txn-99"));
    }

    @Test
    void alertNotFound_returns404() throws Exception {
        mvc.perform(get("/test/not-found/alert/alert-77"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Fraud alert not found: alert-77"));
    }

    // InsufficientBalanceException - 422

    @Test
    void insufficientBalance_returns422() throws Exception {
        mvc.perform(get("/test/insufficient-balance"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Unprocessable Entity"))
                .andExpect(jsonPath("$.message").value(
                        "User user-42 has insufficient balance: required 500.00, available 200.00"));
    }

    // TransactionQueueFullException - 503

    @Test
    void queueFull_returns503() throws Exception {
        mvc.perform(get("/test/queue-full"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("Transaction queue is full! Try again shortly."));
    }

    // Validation (@Valid) - 400

    @Test
    void validationFailure_returns400WithDetails() throws Exception {
        mvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details[0]").value("name must not be blank"));
    }

    @Test
    void missingNameField_returns400() throws Exception {
        mvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":null}"))
                .andExpect(status().isBadRequest());
    }

    // BadCredentialsException - 401

    @Test
    void badCredentials_returns401() throws Exception {
        mvc.perform(get("/test/bad-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    // AccessDeniedException - 403

    @Test
    void accessDenied_returns403() throws Exception {
        mvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    // ConstraintViolationException - 400

    @Test
    void constraintViolation_returns400WithDetails() throws Exception {
        mvc.perform(get("/test/constraint-violation").param("qty", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Constraint violation"))
                .andExpect(jsonPath("$.details[0]", org.hamcrest.Matchers.endsWith("qty: qty must be at least 1")));
    }

    // Unhandled Exception - 500

    @Test
    void unexpectedError_returns500() throws Exception {
        mvc.perform(get("/test/general-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    // ApiErrorResponse shape

    @Test
    void errorResponse_alwaysHasRequiredFields() throws Exception {
        mvc.perform(get("/test/not-found/user/x"))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // Unit tests — no web context needed

    @Test
    void resourceNotFoundException_factoryMessages() {
        assertEquals("User not found: u1",          ResourceNotFoundException.forUser("u1").getMessage());
        assertEquals("Transaction not found: t1",   ResourceNotFoundException.forTransaction("t1").getMessage());
        assertEquals("Fraud alert not found: a1",   ResourceNotFoundException.forAlert("a1").getMessage());
    }

    @Test
    void resourceNotFoundException_customMessage() {
        var ex = new ResourceNotFoundException("Custom message");
        assertEquals("Custom message", ex.getMessage());
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    void insufficientBalanceException_messageFormat() {
        var ex = new InsufficientBalanceException("user-1",
                new BigDecimal("100.00"), new BigDecimal("50.00"));
        assertEquals("User user-1 has insufficient balance: required 100.00, available 50.00",
                ex.getMessage());
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    void transactionQueueFullException_defaultMessage() {
        var ex = new TransactionQueueFullException();
        assertEquals("Transaction queue is full! Try again shortly.", ex.getMessage());
        assertInstanceOf(RuntimeException.class, ex);
    }
}