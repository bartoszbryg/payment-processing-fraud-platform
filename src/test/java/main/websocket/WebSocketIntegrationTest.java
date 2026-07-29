package main.websocket;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import main.application.states.FraudRuleType;
import main.databaseModel.AppUser;
import main.databaseModel.MerchantBlacklist;
import main.dto.request.PaymentRequest;
import main.dto.request.RegisterRequest;
import main.dto.response.UserResponse;
import main.repository.AppUserRepository;
import main.repository.MerchantBlacklistRepository;
import main.security.JwtTokenProvider;
import main.service.AuthService;
import main.service.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/*
    End-to-end proof that a fraud alert reaches a connected dashboard over STOMP without
    polling: submit a payment through the real PaymentService -> TransactionQueue ->
    TransactionWorkerPool pipeline and observe the broadcast on the wire.

    The CRITICAL case uses a blacklisted merchant rather than a large amount because
    BlacklistedMerchantRule fires a deterministic 100-point score with no dependency on
    other rules' thresholds (velocity, location, graph) — CRITICAL is included in the
    same "HIGH or CRITICAL" broadcast gate as HIGH, so this exercises the same path.
*/
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebSocketIntegrationTest {

    @LocalServerPort
    int port;

    @MockBean RestTemplate restTemplate; // prevents real HTTP calls to the ML scoring service

    @Autowired AuthService authService;
    @Autowired PaymentService paymentService;
    @Autowired AppUserRepository appUserRepository;
    @Autowired MerchantBlacklistRepository merchantBlacklistRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;

    WebSocketStompClient stompClient;
    StompSession session;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(
            new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        // Without this, deserializing the message's Instant timestamp field throws inside
        // DefaultStompSession's frame handling — silently, since the client only logs it.
        converter.getObjectMapper().registerModule(new JavaTimeModule());
        stompClient.setMessageConverter(converter);
    }

    @AfterEach
    void tearDown() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    @Test
    void criticalTransaction_broadcastsToFraudAlertsTopicWithinTwoSeconds() throws Exception {
        UserResponse alice = authService.register(
            registerRequest("Alice Smith", "alice-ws@example.com", "alice-ws"));
        merchantBlacklistRepository.save(MerchantBlacklist.builder()
            .merchantName("ShadyStoreWs")
            .reason("Known fraud ring")
            .build());

        session = connect(jwtFor(createAnalyst("analyst-ws-1")));
        AtomicReference<FraudAlertBroadcastMessage> received = subscribe(session, "/topic/fraud-alerts");
        awaitSubscriptionRegistered();

        paymentService.submitPayment(alice.getId(), merchantRequest("ShadyStoreWs", new BigDecimal("50.00")));

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(received.get()).isNotNull());

        FraudAlertBroadcastMessage message = received.get();
        assertThat(message.getUserId()).isEqualTo(alice.getId());
        assertThat(message.getRuleTypes()).contains(FraudRuleType.BLACKLISTED_MERCHANT);
        assertThat(message.getFraudScore()).isGreaterThanOrEqualTo(70.0);
    }

    @Test
    void lowRiskTransaction_producesNoWebSocketTraffic() throws Exception {
        UserResponse bob = authService.register(
            registerRequest("Bob Jones", "bob-ws@example.com", "bob-ws"));

        session = connect(jwtFor(createAnalyst("analyst-ws-2")));
        AtomicReference<FraudAlertBroadcastMessage> received = subscribe(session, "/topic/fraud-alerts");
        awaitSubscriptionRegistered();

        paymentService.submitPayment(bob.getId(), merchantRequest("Amazon", new BigDecimal("25.00")));

        // Give the worker pool a full cycle to (not) act, then assert nothing arrived.
        Thread.sleep(2000);
        assertThat(received.get()).isNull();
    }

    private AtomicReference<FraudAlertBroadcastMessage> subscribe(StompSession session, String destination) {
        AtomicReference<FraudAlertBroadcastMessage> received = new AtomicReference<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return FraudAlertBroadcastMessage.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.set((FraudAlertBroadcastMessage) payload);
            }
        });
        return received;
    }

    // The SUBSCRIBE frame is sent asynchronously and the simple in-memory broker (unlike a
    // full STOMP broker relay) doesn't support RECEIPT frames to confirm registration, so
    // there is no ack to wait on. The worker pool can process a queued transaction in single
    // digit milliseconds, which is fast enough to race an unconfirmed subscription — this
    // fixed pause keeps the test well clear of that window without adding broker-specific
    // production code just to support a test.
    private void awaitSubscriptionRegistered() throws InterruptedException {
        Thread.sleep(300);
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        return stompClient.connectAsync(
                "http://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);
    }

    private String jwtFor(String username) {
        var appUser = appUserRepository.findByUsername(username).orElseThrow();
        var authorities = appUser.getRoles().stream().map(SimpleGrantedAuthority::new).toList();
        var authentication = new UsernamePasswordAuthenticationToken(username, null, authorities);
        return jwtTokenProvider.generateToken(authentication);
    }

    private String createAnalyst(String username) {
        appUserRepository.saveAndFlush(AppUser.builder()
            .username(username)
            .email(username + "@bank.internal")
            .password("test-password-hash")
            .roles(Set.of("ROLE_ANALYST"))
            .build());
        return username;
    }

    private RegisterRequest registerRequest(String name, String email, String username) {
        RegisterRequest req = new RegisterRequest();
        req.setName(name);
        req.setEmail(email);
        req.setUsername(username);
        req.setPassword("S3cur3P@ss!");
        req.setPhoneNumber("+14155552671");
        req.setInitialBalance(new BigDecimal("5000.00"));
        req.setHomeCountry("US");
        req.setHomeCity("New York");
        return req;
    }

    private PaymentRequest merchantRequest(String merchant, BigDecimal amount) {
        PaymentRequest req = new PaymentRequest();
        req.setAmount(amount);
        req.setMerchant(merchant);
        req.setLocation("New York");
        req.setCountry("US");
        return req;
    }
}