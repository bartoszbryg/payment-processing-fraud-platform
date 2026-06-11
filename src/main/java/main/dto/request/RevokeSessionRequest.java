package main.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/*
    Sent when a user wants to log out a specific device from their "active sessions" screen.
    The service verifies that sessionId belongs to the requesting user before deactivating it -
    a user cannot revoke someone else's session by guessing an ID.
*/
@Data
public class RevokeSessionRequest {

    @NotBlank
    private String sessionId;
    
}