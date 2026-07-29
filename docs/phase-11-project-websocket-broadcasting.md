# Phase 11 — WebSocket / STOMP Real-Time Alert Broadcast

Persisting a fraud alert is necessary for audit history, but it is not enough for an analyst watching live payment traffic. Polling an HTTP endpoint adds delay, repeats work when nothing has changed, and leaves a gap between a fraud decision and the moment a dashboard notices it. HIGH and CRITICAL decisions already happen asynchronously in the worker pool, so the notification path must begin there, cross a message broker, preserve the authenticated analyst's identity, and deliver one useful aggregate payload per transaction. That is what Phase 11 builds.

---

## What Was Added

```text
├── pom.xml
└── src/
    ├── main/java/main/
    │   ├── config/
    │   │   └── WebSocketConfig.java
    │   ├── queue/
    │   │   └── TransactionWorkerPool.java
    │   ├── security/
    │   │   ├── SecurityConfig.java
    │   │   ├── StompAuthChannelInterceptor.java
    │   │   └── WebSocketSecurityConfig.java
    │   └── websocket/
    │       ├── FraudAlertBroadcaster.java
    │       └── FraudAlertBroadcastMessage.java
    └── test/java/main/websocket/
        ├── FraudAlertBroadcasterTest.java
        └── WebSocketIntegrationTest.java
```

`spring-boot-starter-websocket` provides Spring's STOMP and WebSocket infrastructure. Message-level authorization comes from the explicit `spring-security-messaging` dependency already declared in `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-messaging</artifactId>
</dependency>
```

This is not the same security layer as the servlet filter chain. The HTTP handshake and SockJS fallback begin as ordinary HTTP requests; STOMP `CONNECT` and `SUBSCRIBE` frames are messages on the client inbound channel and need their own authentication and authorization.

---

## `WebSocketConfig`

`WebSocketConfig` enables Spring's broker-backed WebSocket support and defines the public protocol surface:

```java
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
            .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
```

`/ws` is the connection endpoint. `.withSockJS()` adds HTTP-based fallback transports for clients behind proxies that cannot sustain a raw WebSocket connection.

The in-memory simple broker owns `/topic`. Server code publishes fraud alerts beneath that prefix and dashboard clients subscribe there. `/app` is reserved for future client-to-server message handlers; Phase 11 does not expose any application destination yet, but registering the prefix now establishes the protocol boundary without changing it later.

`@Order(Ordered.HIGHEST_PRECEDENCE)` is security-critical. `WebSocketConfig` and `WebSocketSecurityConfig` are two independent `WebSocketMessageBrokerConfigurer` beans. Spring's `DelegatingWebSocketMessageBrokerConfiguration` composes both by looping over its configurer list and applying each `configureClientInboundChannel()` contribution to the same shared inbound channel. The authentication interceptor must be registered first so it can convert the Bearer token on a STOMP `CONNECT` frame into a `Principal` before the later authorization interceptor evaluates that frame or any subscription.

---

## `SecurityConfig` — Permit the Handshake, Secure the Frames

The servlet security chain explicitly permits `/ws/**`:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/auth/login", "/auth/register").permitAll()
    .requestMatchers("/h2-console/**").permitAll()
    .requestMatchers("/actuator/health").permitAll()
    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
    // SockJS handshake/polling needs plain HTTP access before a STOMP session
    // exists. Real auth happens per-frame in StompAuthChannelInterceptor and is
    // enforced by WebSocketSecurityConfig.
    .requestMatchers("/ws/**").permitAll()
    .anyRequest().authenticated()
)
```

That does not make fraud topics public. A SockJS client may need several unauthenticated HTTP requests before a STOMP session exists, so requiring a servlet Bearer token on every transport request would put authentication at the wrong layer. The token instead travels in the native STOMP `Authorization` header. `StompAuthChannelInterceptor` authenticates it, and `WebSocketSecurityConfig` authorizes the resulting messages.

The REST API is stateless and disables servlet CSRF:

```java
http.csrf(AbstractHttpConfigurer::disable)
    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

The WebSocket security design follows the same stateless-JWT constraint. There is no server-side HTTP session from which a STOMP CSRF token could be recovered.

---

## `StompAuthChannelInterceptor`

The authentication interceptor acts only on STOMP `CONNECT`. It extracts a native Bearer token, validates it through the same `JwtTokenProvider` used by HTTP security, loads the current user, rejects disabled or deleted accounts by leaving the frame unauthenticated, and attaches an `Authentication` to the accessor when validation succeeds:

```java
@Override
public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

    if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
        String token = extractToken(accessor);
        String username = StringUtils.hasText(token) ? tokenProvider.validateAndGetUsername(token) : null;

        if (username != null) {
            try {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                if (userDetails.isEnabled()) {
                    accessor.setUser(new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities()));
                } else {
                    log.warn("STOMP CONNECT token valid but account is disabled: {}", username);
                }
            } catch (UsernameNotFoundException e) {
                log.warn("STOMP CONNECT token valid but user no longer exists: {}", e.getMessage());
            }
        }
    }

    return message;
}
```

The header parser is deliberately strict:

```java
private String extractToken(StompHeaderAccessor accessor) {
    String header = accessor.getFirstNativeHeader("Authorization");
    if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
        return header.substring(7);
    }
    return null;
}
```

Only the first native `Authorization` header and the exact `Bearer ` scheme are accepted. Spring carries the attached user forward for later frames in the same STOMP session, so `SUBSCRIBE` does not need to resend the JWT.

---

## `WebSocketSecurityConfig`

`WebSocketSecurityConfig` uses Spring Security's current `AuthorizationManager<Message<?>>` API. It is a plain `@Configuration` implementing `WebSocketMessageBrokerConfigurer`; it does not extend `AbstractSecurityWebSocketMessageBrokerConfigurer`:

```java
@Bean
public static AuthorizationManager<Message<?>> messageAuthorizationManager() {
    return MessageMatcherDelegatingAuthorizationManager.builder()
        .simpSubscribeDestMatchers("/topic/fraud-alerts").hasAnyRole("ADMIN", "ANALYST")
        .simpSubscribeDestMatchers("/topic/fraud-alerts/*").hasAnyRole("ADMIN", "ANALYST")
        .anyMessage().authenticated()
        .build();
}
```

Both fraud destinations have the same explicit role boundary:

- `/topic/fraud-alerts` is the shared analyst feed.
- `/topic/fraud-alerts/*` is a one-segment targeted feed such as `/topic/fraud-alerts/user-001`.
- Both require either `ROLE_ADMIN` or `ROLE_ANALYST`.
- Every other inbound message still requires authentication.

Customers therefore cannot subscribe to either the global feed or another user's targeted feed merely because they hold a valid customer JWT.

The class's own header comment documents why the standard annotation is intentionally not used:

```java
/*
    Message-level (STOMP frame) authorization, separate from SecurityConfig's HTTP-level rules.
    Fraud alert topics are analyst/admin-only. Customers may be authenticated, but they must
    not be able to subscribe to the global alert stream or another user's targeted fraud feed.
    sameOriginDisabled() is true because this API is stateless JWT with no server-side session,
    so the CSRF-token-in-session check STOMP CSRF protection relies on does not apply here
    (same reasoning as SecurityConfig disabling CSRF for the REST API).

    The bare shared topic and the one-segment per-user topic are listed separately so their
    intent stays obvious. User IDs are always exactly one path segment, so "/*" is sufficient
    for targeted feeds without opening deeper wildcard paths.

    This uses Spring Security's AuthorizationManager API instead of the deprecated
    AbstractSecurityWebSocketMessageBrokerConfigurer adapter. We wire the interceptors manually
    because @EnableWebSocketSecurity currently makes STOMP CONNECT CSRF mandatory; this API is
    stateless JWT over STOMP headers, and SecurityConfig already disables servlet CSRF.
*/
```

Instead of relying on `@EnableWebSocketSecurity`, the class installs Spring Security's channel interceptors itself:

```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    AuthorizationChannelInterceptor authz = new AuthorizationChannelInterceptor(authorizationManager);
    authz.setAuthorizationEventPublisher(new SpringAuthorizationEventPublisher(applicationContext));
    registration.interceptors(new SecurityContextChannelInterceptor(), authz);
}
```

`SecurityContextChannelInterceptor` exposes the message user through Spring Security's context while a frame is handled. `AuthorizationChannelInterceptor` applies the static `AuthorizationManager`. Its `SpringAuthorizationEventPublisher` publishes granted and denied authorization events through the application context.

`AuthenticationPrincipalArgumentResolver` is also registered for future message-handling methods:

```java
@Override
public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
    argumentResolvers.add(new AuthenticationPrincipalArgumentResolver());
}
```

Phase 11 publishes server-to-client messages only, but a later `@MessageMapping` handler can now resolve the authenticated principal without changing the security infrastructure.

---

## Security Deep-Dive — One Shared Inbound Channel

The complete inbound sequence is:

```text
SockJS/WebSocket HTTP handshake to /ws/**
        │
        ├── SecurityConfig permits the transport request
        ▼
STOMP CONNECT with Authorization: Bearer <JWT>
        │
        ├── StompAuthChannelInterceptor validates JWT and calls accessor.setUser(...)
        ▼
SecurityContextChannelInterceptor exposes the message Principal
        │
        ├── AuthorizationChannelInterceptor applies AuthorizationManager<Message<?>>
        ▼
STOMP SUBSCRIBE
        │
        ├── /topic/fraud-alerts      → ADMIN or ANALYST
        ├── /topic/fraud-alerts/*    → ADMIN or ANALYST
        └── every other message      → authenticated
```

There are not two different inbound channels. `WebSocketConfig.configureClientInboundChannel()` and `WebSocketSecurityConfig.configureClientInboundChannel()` both modify the same registration through Spring's configurer-composition loop. Their effective interceptor ordering is:

```text
1. StompAuthChannelInterceptor
2. SecurityContextChannelInterceptor
3. AuthorizationChannelInterceptor
```

If authorization ran before authentication, a valid analyst token would still look anonymous when the `CONNECT` frame was checked. The explicit order on `WebSocketConfig` prevents that inversion.

Manual interceptor registration also keeps the stateless design coherent. `@EnableWebSocketSecurity` currently requires CSRF on STOMP `CONNECT`, but the application deliberately has no server-side session and already disables servlet CSRF for JWT-authenticated REST requests. Installing the two required security interceptors directly preserves message authorization without introducing a session-only CSRF mechanism that clients cannot satisfy.

---

## `FraudAlertBroadcastMessage`

One transaction can fire several fraud rules. Broadcasting one frame per database alert would force clients to group and de-duplicate messages, so the wire DTO aggregates all rule types and descriptions into one transaction-level message:

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FraudAlertBroadcastMessage {

    private String transactionId;
    private String userId;
    private RiskLevel riskLevel;
    private double fraudScore;
    private List<FraudRuleType> ruleTypes;
    private String description;
    private Instant timestamp;
}
```

`@JsonInclude(NON_NULL)` keeps optional fields out of the payload. The no-argument constructor and setters generated by Lombok allow Jackson to deserialize the same payload in integration tests and dashboard clients.

The aggregate factory removes duplicate rule types and joins distinct explanations:

```java
public static FraudAlertBroadcastMessage from(List<FraudAlert> alerts, Transaction transaction) {
    List<FraudRuleType> ruleTypes = alerts.stream()
        .map(FraudAlert::getRuleType)
        .distinct()
        .toList();

    String description = alerts.stream()
        .map(FraudAlert::getDescription)
        .distinct()
        .collect(Collectors.joining("; "));

    return FraudAlertBroadcastMessage.builder()
        .transactionId(transaction.getId())
        .userId(transaction.getSender().getId())
        .riskLevel(transaction.getRiskLevel())
        .fraudScore(transaction.getFraudScore())
        .ruleTypes(ruleTypes)
        .description(description)
        .timestamp(transaction.getProcessedAt() != null ? transaction.getProcessedAt() : Instant.now())
        .build();
}
```

The transaction is the source of the final cumulative score and risk level. `processedAt` becomes the message timestamp when available; `Instant.now()` is a defensive fallback. A separate single-alert factory supports callers that have only a `FraudAlert`, using the attached transaction when present and falling back to alert-level values when it is not.

---

## `FraudAlertBroadcaster`

The broadcaster is a small service around `SimpMessagingTemplate`:

```java
private static final String ALL_ALERTS_TOPIC = "/topic/fraud-alerts";
private static final String USER_ALERTS_TOPIC_PREFIX = "/topic/fraud-alerts/";

public void broadcast(List<FraudAlert> alerts, Transaction transaction) {
    if (alerts.isEmpty()) return;

    FraudAlertBroadcastMessage message = FraudAlertBroadcastMessage.from(alerts, transaction);

    messagingTemplate.convertAndSend(ALL_ALERTS_TOPIC, message);
    messagingTemplate.convertAndSend(USER_ALERTS_TOPIC_PREFIX + transaction.getSender().getId(), message);

    log.info("Broadcast fraud alert: txn={}, riskLevel={}, ruleCount={}",
        transaction.getId(), transaction.getRiskLevel(), alerts.size());
}
```

An empty alert list produces no traffic. A non-empty list becomes one aggregate message sent to two destinations:

| Destination | Purpose | Authorized subscribers |
|---|---|---|
| `/topic/fraud-alerts` | Shared fraud operations feed | `ADMIN`, `ANALYST` |
| `/topic/fraud-alerts/{senderId}` | Targeted sender feed for scoped dashboards | `ADMIN`, `ANALYST` |

The broadcaster does not decide whether a risk level deserves live attention. Keeping that policy in the worker means the broadcaster has one responsibility: convert an already-approved alert event into STOMP messages.

---

## Worker-Pool Wiring

`TransactionWorkerPool` is the bridge from a committed fraud decision to the real-time channel. `applyFraudResult()` first attaches alerts to their transaction, persists non-empty alert lists, and copies the engine's final score and risk level onto the transaction:

```java
List<FraudAlert> alerts = result.alerts();
alerts.forEach(alert -> {
    alert.setTransaction(transaction);
    if (alert.getUserId() == null && transaction.getSender() != null) {
        alert.setUserId(transaction.getSender().getId());
    }
});

if (!alerts.isEmpty()) {
    fraudAlertRepository.saveAll(alerts);
}

transaction.setFraudScore(result.fraudScore());
transaction.setRiskLevel(result.riskLevel());
transaction.setProcessedAt(Instant.now());
transaction.setProcessingTimeMs((System.nanoTime() - startNs) / 1_000_000);
```

Only then does it apply the live-dashboard gate:

```java
// Only HIGH/CRITICAL reach analyst dashboards in real time — LOW/MEDIUM would be
// noise on a screen meant for triage.
if (result.isHighRisk()) {
    fraudAlertBroadcaster.broadcast(alerts, transaction);
}
```

`FraudAnalysisResult.isHighRisk()` covers HIGH and CRITICAL decisions. LOW and MEDIUM transactions may still be persisted and processed normally, but they do not generate WebSocket traffic. That avoids turning the analyst feed into a duplicate stream of every payment.

The worker continues through its normal outcome routing after broadcasting:

```java
if (result.isCritical()) {
    transaction.setStatus(TransactionStatus.FRAUD_BLOCKED);
    transaction.setDeclineReason("Fraud score reached CRITICAL risk level");
    return;
}

if (result.riskLevel() == RiskLevel.HIGH) {
    transaction.setStatus(TransactionStatus.FLAGGED_FOR_REVIEW);
    return;
}
```

CRITICAL transactions are blocked; HIGH transactions are flagged for review. The broadcast payload already contains the engine's risk level and score, so dashboards do not need to wait for a follow-up polling cycle to understand the decision.

---

## End-to-End Wiring

```text
PaymentService.submitPayment(...)
        │
        ▼
TransactionQueue stores transaction ID
        │
        ▼
TransactionWorkerPool.processTransaction(...)
        │
        ▼
FraudDetectionEngine returns score, risk level, and alerts
        │
        ├── LOW / MEDIUM → no WebSocket broadcast
        │
        └── HIGH / CRITICAL
                │
                ▼
        FraudAlertBroadcaster
                │
                ├── /topic/fraud-alerts
                └── /topic/fraud-alerts/{senderId}
                        │
                        ▼
                authenticated ADMIN / ANALYST dashboard
```

There is no controller in this path. The application publishes directly from the asynchronous decision pipeline through `SimpMessagingTemplate`, and Spring's simple broker fans the message out to authorized subscriptions.

---

## Test Suite

### `FraudAlertBroadcasterTest`

Four Mockito tests exercise the broadcaster without starting Spring:

- `highRiskAlert_broadcastsToBothTopics` captures the shared-topic message and checks transaction ID, user ID, risk, score, rule types, and timestamp.
- `criticalRiskAlert_broadcastsToBothTopics` verifies that CRITICAL alerts also produce exactly two sends.
- `multipleAlerts_ruleTypesAggregatedInMessage` verifies that multiple fired rules appear together in one payload.
- `emptyAlerts_neverBroadcasts` verifies the negative path with `verifyNoInteractions`.

### `WebSocketIntegrationTest`

Two full-context tests start the application on a random port and connect with a real SockJS/STOMP client:

```java
session = connect(jwtFor(createAnalyst("analyst-ws-1")));
AtomicReference<FraudAlertBroadcastMessage> received =
    subscribe(session, "/topic/fraud-alerts");
awaitSubscriptionRegistered();

paymentService.submitPayment(
    alice.getId(),
    merchantRequest("ShadyStoreWs", new BigDecimal("50.00")));

await().atMost(2, TimeUnit.SECONDS)
    .untilAsserted(() -> assertThat(received.get()).isNotNull());
```

`criticalTransaction_broadcastsToFraudAlertsTopicWithinTwoSeconds` blacklists a deterministic merchant, connects as `ROLE_ANALYST`, submits a payment through the real service/queue/worker pipeline, and asserts that the received frame identifies the user, includes `BLACKLISTED_MERCHANT`, and carries a high-risk score.

`lowRiskTransaction_producesNoWebSocketTraffic` submits an ordinary Amazon payment through the same pipeline and verifies that the subscription remains empty.

Three integration details are worth calling out:

1. **The client uses the production authentication shape.** `connect()` sends `Authorization: Bearer <JWT>` as a native STOMP header. The test creates an analyst principal with `ROLE_ANALYST`, so it proves the role matcher and not merely a permissive connection.

2. **Java time support is registered on the client.** The message contains an `Instant`. `MappingJackson2MessageConverter` receives a `JavaTimeModule`, preventing frame deserialization from failing inside the asynchronous STOMP client.

3. **Subscription registration has an explicit race guard.** Spring's in-memory simple broker does not support STOMP receipt frames for this subscription path. The test waits 300 milliseconds after subscribing so a worker capable of processing in single-digit milliseconds cannot publish before the broker has registered the subscription.

The ML scoring `RestTemplate` is mocked to prevent real external HTTP calls. Everything else in the fraud-alert path—including the embedded server, SockJS transport, STOMP broker, authentication interceptor, authorization manager, database repositories, payment service, queue, worker pool, and broadcaster—is real.

---

## Correctness: Test Results

The complete project suite was run with `mvn verify` after the permanent JaCoCo gate was added:

```text
[INFO] Tests run: 382, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.12:report (report) @ payment-fraud-detection ---
[INFO] Analyzed bundle 'Real-Time Payment Fraud Detection System' with 83 classes
[INFO] --- jacoco:0.8.12:check (check) @ payment-fraud-detection ---
[INFO] Analyzed bundle 'payment-fraud-detection' with 83 classes
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
[INFO] Total time:  01:56 min
```

Run only the WebSocket tests:

```powershell
mvn test -Dtest="FraudAlertBroadcasterTest,WebSocketIntegrationTest"
```

Run the full build, generate the JaCoCo report, and enforce the 50% instruction-coverage floor:

```powershell
mvn verify
```

The coverage threshold is a regression floor, not the project's quality target. Risk-sensitive branch coverage should continue to improve in decision-heavy worker and graph code rather than through low-value tests of plain DTO accessors.

---

## What Forces the Next Step

The WebSocket is live. Submitting a payment that the fraud engine classifies as HIGH or CRITICAL causes a message to arrive on /topic/fraud-alerts within one processing cycle, and LOW/MEDIUM transactions produce no WebSocket traffic at all. But every service in this system — AuthService, PaymentService, FraudAlertService, UserService, DashboardService — is still only reachable by calling it directly from a test or another Java class. There is no controller anywhere in the codebase, so nothing here is reachable from a browser, a mobile app, or curl. Phase 12 builds the REST layer — AuthController, UserController, PaymentController, FraudAlertController, and DashboardController, each thin enough to only validate, delegate, and map exceptions to HTTP status codes through an expanded GlobalExceptionHandler. After that, Phase 13 fills the database with realistic starting data so the new endpoints have something to return on a fresh start.
