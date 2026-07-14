# Phase 6 — Exception Handling

Services need a clean way to signal failure. The wrong options are returning `null` (caller forgets to check), returning a sentinel like `-1` (caller has to know the convention), or scattering `try/catch` blocks in every controller (error formatting logic duplicated everywhere). The right option is typed exceptions that get caught in one place and converted to a structured HTTP response. That is what I built.

---

## What Was Added

```
├── src/
│   ├── main/java/main/
│   │   ├── exception/
│   │   │   ├── ResourceNotFoundException.java
│   │   │   ├── InsufficientBalanceException.java
│   │   │   └── GlobalExceptionHandler.java
│   │   └── queue/
│   │       └── TransactionQueue.java          ← stub; inner exception defined here
│   └── test/java/main/
│       └── exception/
│           ├── ExceptionHandlingTest.java
│           └── ThrowingController.java        ← test-only controller
```

---

## The Three Exception Classes

### `ResourceNotFoundException` — HTTP 404

Thrown when a lookup by ID finds nothing. Extends `RuntimeException` so it is unchecked. Therefore, callers do not have to declare or catch it, Spring propagates it up to the handler automatically.

```java
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException forUser(String id) {
        return new ResourceNotFoundException("User not found: " + id);
    }

    public static ResourceNotFoundException forTransaction(String id) {
        return new ResourceNotFoundException("Transaction not found: " + id);
    }

    public static ResourceNotFoundException forAlert(String id) {
        return new ResourceNotFoundException("Fraud alert not found: " + id);
    }
}
```

**Static factory methods** (`forUser`, `forTransaction`, `forAlert`) are a standard Java idiom. Instead of writing `new ResourceNotFoundException("User not found: " + id)` in every service, you write `ResourceNotFoundException.forUser(id)`. One place to change the message format if it ever needs to change.

---

### `InsufficientBalanceException` — HTTP 422

Thrown in the payment service before debiting a user whose balance is too low. 422 Unprocessable Entity is more precise than 400 Bad Request here because the request was syntactically valid, it just cannot be fulfilled given the current state.

```java
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String userId, BigDecimal required, BigDecimal available) {
        super(String.format("User %s has insufficient balance: required %.2f, available %.2f",
                userId, required, available));
    }
}
```

The constructor carries enough context to make the error message self-explanatory without any additional response fields. A caller seeing `"User u-42 has insufficient balance: required 500.00, available 200.00"` knows exactly what happened.

---

### `TransactionQueue.TransactionQueueFullException` — HTTP 503

Defined as a static nested class inside `TransactionQueue`. Nesting it there is idiomatic Java because the exception is semantically owned by the queue. `GlobalExceptionHandler` imports it as `TransactionQueue.TransactionQueueFullException`.

```java
public class TransactionQueue {

    // Full queue implementation comes in Phase 7
    public static class TransactionQueueFullException extends RuntimeException {
        public TransactionQueueFullException() {
            super("Transaction queue is full! Try again shortly.");
        }
    }
}
```

`TransactionQueue` itself is a placeholder for Phase 7. The exception is defined now so `GlobalExceptionHandler` can reference it without a forward dependency.

---

## `GlobalExceptionHandler` — The Central Catch Point

`@RestControllerAdvice` is a Spring annotation that registers this class as an exception interceptor for all controllers in the application. Every `@ExceptionHandler` method declares which exception type it handles — Spring picks the most specific matching handler automatically.

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest req) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), req, null);
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ApiErrorResponse> handleInsufficientBalance(
            InsufficientBalanceException ex, HttpServletRequest req) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), req, null);
    }

    @ExceptionHandler(TransactionQueueFullException.class)
    public ResponseEntity<ApiErrorResponse> handleQueueFull(
            TransactionQueueFullException ex, HttpServletRequest req) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .toList();
        return error(HttpStatus.BAD_REQUEST, "Validation failed", req, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest req) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .toList();
        return error(HttpStatus.BAD_REQUEST, "Constraint violation", req, details);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest req) {
        return error(HttpStatus.UNAUTHORIZED, "Invalid credentials", req, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest req) {
        return error(HttpStatus.FORBIDDEN, "Access denied", req, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneral(
            Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}: {}", req.getRequestURI(), ex.getMessage(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req, null);
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String message,
                                                    HttpServletRequest req, List<String> details) {
        return ResponseEntity.status(status).body(ApiErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(req.getRequestURI())
                .timestamp(Instant.now())
                .details(details)
                .build());
    }
}
```

### Handler → HTTP status mapping

| Exception | HTTP Status | When it fires |
|---|---|---|
| `ResourceNotFoundException` | 404 Not Found | User, transaction, or alert not found by ID |
| `InsufficientBalanceException` | 422 Unprocessable Entity | Payment exceeds sender's balance |
| `TransactionQueueFullException` | 503 Service Unavailable | Processing queue at capacity |
| `MethodArgumentNotValidException` | 400 Bad Request | `@Valid` on a `@RequestBody` fails |
| `ConstraintViolationException` | 400 Bad Request | `@Validated` on path/query params fails |
| `BadCredentialsException` | 401 Unauthorized | Wrong username or password |
| `AccessDeniedException` | 403 Forbidden | Authenticated but lacks permission |
| `Exception` (fallback) | 500 Internal Server Error | Anything else — logged with full stack trace |

### Why the fallback logs but the others don't

The specific handlers deal with expected failures. A user not found is a normal business condition, not a bug. The fallback `Exception` handler catches genuinely unexpected failures, so it logs the full stack trace at ERROR level. The specific handlers deliberately do not log because they would generate noise in production for events that happen all the time.

### The `error(...)` private method

All eight handlers call the same private helper. Without it, building `ApiErrorResponse` would be duplicated in every handler. The helper takes `status`, `message`, `req`, and `details` which are the only things that differ per handler and handles everything else uniformly.

---

## What the `details` Field Is For

`details` is `null` for most errors. It is only populated for validation failures where multiple fields can fail simultaneously, and the caller needs to know which ones:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/payments",
  "timestamp": "2026-06-17T16:01:10.885Z",
  "details": [
    "amount must be greater than 0.00",
    "location must not be blank"
  ]
}
```

For all other errors, `details` is absent from the JSON output (Jackson omits null fields by default when `@JsonInclude(NON_NULL)` is set, or the field simply serializes as `null`).

---

## Fix Made to `Main.java`

The project package structure looks like this:

```
src/main/java/
└── main/                    ← root package
    ├── application/
    │   └── Main.java        ← @SpringBootApplication lives here
    ├── exception/
    ├── queue/
    ├── dto/
    └── repository/
```

`@SpringBootApplication` scans the package it is declared in **and all sub-packages of it**. Since `Main.java` is in `main.application`, Spring scans `main.application.*` — but `main.exception`, `main.queue`, `main.dto`, and `main.repository` are **siblings** of `main.application`, not children. They are outside the scan path.

Previous phases worked anyway because the tests used `@DataJpaTest`, which bootstraps from `TestApplication` located in the root `main` package. `TestApplication` scans all of `main.*` correctly. The production app was never actually started, so the missing scan was never hit.

Adding `scanBasePackages = "main"` tells Spring to scan from the root `main` package regardless of where `Main.java` physically lives, matching what `TestApplication` already does:

```java
// before — scans only main.application.*
@SpringBootApplication
public class Main { ... }

// after — scans all main.* subpackages
@SpringBootApplication(scanBasePackages = "main")
public class Main { ... }
```

Without this fix, `GlobalExceptionHandler` would not be registered when the actual application starts, and all exceptions would fall through to Spring's default whitelabel error page.

---

## Testing

### Test structure

Two files in `src/test/java/main/exception/`:

**`ThrowingController`** — a test-only `@RestController` with one endpoint per exception type. Lives in `src/test/java` so it is never compiled into the production JAR. Annotated with `@Validated` at class level so `@Min` on `@RequestParam` triggers `ConstraintViolationException` (without `@Validated`, parameter constraints are silently ignored).

**`ExceptionHandlingTest`** — uses `@WebMvcTest(ThrowingController.class)` which loads only the web layer (no JPA, no database). `@AutoConfigureMockMvc(addFilters = false)` skips Spring Security's filter chain — correct here because what is being tested is exception mapping, not authentication. The test is about what happens after a request reaches a controller, not whether it gets past the security layer.

### Test coverage

16 tests across three groups:

**HTTP mapping tests** — hit each `ThrowingController` endpoint via `MockMvc`, assert the exact HTTP status, JSON field values, and message content:

```java
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
```

**Shape test** — verifies every error response has all five required `ApiErrorResponse` fields regardless of which exception fired:

```java
@Test
void errorResponse_alwaysHasRequiredFields() throws Exception {
    mvc.perform(get("/test/not-found/user/x"))
            .andExpect(jsonPath("$.status").exists())
            .andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.path").exists())
            .andExpect(jsonPath("$.timestamp").exists());
}
```

**Unit tests** — construct each exception directly (no Spring context, no MockMvc) and assert message format and inheritance:

```java
@Test
void insufficientBalanceException_messageFormat() {
    var ex = new InsufficientBalanceException("user-1",
            new BigDecimal("100.00"), new BigDecimal("50.00"));
    assertEquals("User user-1 has insufficient balance: required 100.00, available 50.00",
            ex.getMessage());
    assertInstanceOf(RuntimeException.class, ex);
}
```

### Things learned from writing the tests

**`assert` vs `assertEquals`** — Java's `assert` keyword is a language feature that only runs when the JVM is started with `-ea` (enable assertions). JUnit's `assertEquals` always runs. Maven Surefire enables `-ea` by default, which is why bare `assert` statements appeared to work — but they would silently no-op in an IDE with assertions disabled. All unit tests use `assertEquals`/`assertInstanceOf`.

**`MethodArgumentNotValidException` vs `ConstraintViolationException`** — these are two different exceptions for two different validation triggers:
- `MethodArgumentNotValidException` fires when `@Valid` is used on a `@RequestBody` parameter
- `ConstraintViolationException` fires when `@Validated` is on the controller class and constraints like `@Min` are on `@RequestParam` or `@PathVariable`

Both map to 400, but they require separate `@ExceptionHandler` methods because they are unrelated exception types with different APIs for extracting violation details.

**`endsWith` matcher for constraint violation details** — Hibernate Validator builds the property path as `methodName.paramName`, so the `details` entry looks like `"constraintViolation.qty: qty must be at least 1"`. Asserting the full string pins the test to the controller method name. If the method is renamed, the test breaks even though nothing about validation logic changed. Using `endsWith`:

```java
.andExpect(jsonPath("$.details[0]", org.hamcrest.Matchers.endsWith("qty: qty must be at least 1")));
```

This survives a method rename and still proves the field name and message are correct.

**The `ERROR` log in test output is expected** — when `unexpectedError_returns500` runs, it intentionally triggers the fallback `Exception` handler, which calls `log.error(...)`. Maven prints handler logs during test runs. The test passes because the 500 response is exactly what is expected. The stack trace in the output is the handler doing its job correctly, not a test failure.

### How to run

```powershell
mvn test -Dtest=ExceptionHandlingTest
```

### Actual test output (selected)

Maven prints the full MockMvc request/response detail for each test. Two examples worth reading carefully:

**503 — queue full (handler wired correctly):**

```
MockHttpServletRequest:
      HTTP Method = GET
      Request URI = /test/queue-full

Handler:
             Type = main.exception.ThrowingController
           Method = main.exception.ThrowingController#queueFull()

Resolved Exception:
             Type = main.queue.TransactionQueue$TransactionQueueFullException

MockHttpServletResponse:
           Status = 503
     Content type = application/json
             Body = {"status":503,"error":"Service Unavailable",
                     "message":"Transaction queue is full! Try again shortly.",
                     "path":"/test/queue-full","timestamp":"...","details":null}
```

This confirms the full chain: request hits controller → controller throws the exception → `GlobalExceptionHandler` catches `TransactionQueueFullException` → returns structured 503 JSON.

**500 — fallback handler with ERROR log:**

```
ERROR main.exception.GlobalExceptionHandler :
      Unhandled exception on /test/general-error: unexpected boom

java.lang.RuntimeException: unexpected boom
    at main.exception.ThrowingController.generalError(...)
    ...

MockHttpServletResponse:
           Status = 500
             Body = {"status":500,"error":"Internal Server Error",
                     "message":"An unexpected error occurred",
                     "path":"/test/general-error","timestamp":"...","details":null}
```

The stack trace looks alarming but is correct — `handleGeneral` calls `log.error(...)` before returning the 500. The client receives a clean JSON body with no stack trace. The trace stays server-side in the logs where it belongs.

**Final result:**

```
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## What Forces the Next Step

Exceptions are defined. Services will throw them. But before writing service logic, the application needs to know who is calling each endpoint, thus which user is authenticated, what roles they have, and whether their JWT is valid. That is Phase 7: Security.