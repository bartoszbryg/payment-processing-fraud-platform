package main.security;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.messaging.context.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

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
@Configuration
public class WebSocketSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private final ApplicationContext applicationContext;
    private final AuthorizationManager<Message<?>> authorizationManager;

    public WebSocketSecurityConfig(
            ApplicationContext applicationContext,
            AuthorizationManager<Message<?>> authorizationManager) {
        this.applicationContext = applicationContext;
        this.authorizationManager = authorizationManager;
    }

    @Bean
    public static AuthorizationManager<Message<?>> messageAuthorizationManager() {
        return MessageMatcherDelegatingAuthorizationManager.builder()
            .simpSubscribeDestMatchers("/topic/fraud-alerts").hasAnyRole("ADMIN", "ANALYST")
            .simpSubscribeDestMatchers("/topic/fraud-alerts/*").hasAnyRole("ADMIN", "ANALYST")
            .anyMessage().authenticated()
            .build();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(new AuthenticationPrincipalArgumentResolver());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        AuthorizationChannelInterceptor authz = new AuthorizationChannelInterceptor(authorizationManager);
        authz.setAuthorizationEventPublisher(new SpringAuthorizationEventPublisher(applicationContext));
        registration.interceptors(new SecurityContextChannelInterceptor(), authz);
    }
}