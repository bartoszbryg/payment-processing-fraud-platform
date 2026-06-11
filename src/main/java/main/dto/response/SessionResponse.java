package main.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/*
    One entry on the "your active sessions" screen.
    Never includes refreshTokenHash - the hash is an internal server secret.
    The client uses sessionId to request revocation via RevokeSessionRequest.
*/
@Data
@Builder
public class SessionResponse {
    private String sessionId;
    private String deviceName;
    private String deviceFingerprint;
    private String ipAddressIssued;
    private String lastSeenIp;
    private Instant lastSeenAt;
    private Instant expiresAt;
    private boolean active;
    private Instant createdAt;
}