package main.config;

import lombok.RequiredArgsConstructor;
import main.security.StompAuthChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/*
    /ws is the STOMP connection URL. /topic is the broker prefix analysts subscribe under
    (e.g. /topic/fraud-alerts). /app is reserved for future client-to-server messages —
    nothing publishes there yet, but registering it now avoids a breaking config change later.
    SockJS gives clients behind corporate proxies that block raw WebSocket an HTTP fallback.

    @Order(HIGHEST_PRECEDENCE): Spring composes every WebSocketMessageBrokerConfigurer bean's
    configureClientInboundChannel() onto one shared channel, in bean-list order.
    WebSocketSecurityConfig now registers Spring Security's AuthorizationChannelInterceptor
    through the same WebSocketMessageBrokerConfigurer mechanism. This configurer must still
    run first so StompAuthChannelInterceptor can turn the STOMP CONNECT Bearer token into a
    Principal before the authorization interceptor evaluates CONNECT and SUBSCRIBE frames.
*/
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
