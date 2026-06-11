package main.dto.response;

import lombok.Builder;
import lombok.Data;

/*
    Returned once on successful login.
    accessToken is short-lived (minutes) - used on every API call.
    refreshToken is long-lived (days) - used only to obtain a new access token.
    Raw refreshToken is returned here and never stored server-side; only its bcrypt hash is persisted.
    Never exposes the AppUser entity - password hash and roles never leave the server.
*/
@Data
@Builder
public class LoginResponse {
    private String accessToken;
    private String refreshToken; // raw value — client must store securely, server stores only the hash
    private String sessionId; // UserSession.id — used to identify this device on the sessions screen
    private String username;
    private String tokenType; // always "Bearer"
    private long accessTokenExpiresInSeconds;
    private long refreshTokenExpiresInSeconds;
}
