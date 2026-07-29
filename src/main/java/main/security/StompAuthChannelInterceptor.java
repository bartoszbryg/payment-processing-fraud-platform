package main.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/*
    The WebSocket handshake itself is permitAll (see SecurityConfig) because SockJS needs
    plain HTTP access before a STOMP session exists. Authentication happens one level up,
    on the STOMP CONNECT frame: the client sends a Bearer token as a native STOMP header,
    this interceptor validates it the same way JwtAuthenticationFilter does for HTTP, and
    calls accessor.setUser(...). Spring then reuses that Authentication for every later
    frame (SUBSCRIBE, SEND, ...) on the same WebSocket session, so WebSocketSecurityConfig's
    anyMessage().authenticated() has something to check.
*/
@Component
@RequiredArgsConstructor
@Slf4j
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsService userDetailsService;

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

    private String extractToken(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}