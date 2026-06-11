package main.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/*
    Carries credentials plus device context captured at login time.
    Device fields are stored in UserSession so the user can see which devices are logged in
    and the fraud engine can flag logins from new devices or locations.
    Never exposes the AppUser entity, password hash and roles never leave the server.
*/
@Data
@Schema(description = "Login request")
public class LoginRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    // Stored in UserSession.deviceFingerprint - compared on each token refresh to detect theft
    @Schema(description = "Unique device fingerprint", example = "d41d8cd98f00b204")
    private String deviceFingerprint;

    // Human-readable label stored in UserSession.deviceName - shown on "your active sessions" screen
    @Schema(description = "Human-readable device name", example = "John's iPhone 14")
    private String deviceName;

    // Stored as UserSession.ipAddressIssued - mid-session IP change is a fraud signal
    @Schema(description = "Client IP address", example = "192.168.1.1")
    private String ipAddress;
    
}