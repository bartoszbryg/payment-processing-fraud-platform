package main.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/*
    Sent by the client when the access token expires.
    The raw refresh token is looked up by bcrypt-hashing it and comparing against UserSession.refreshTokenHash.
    Never log or store the raw value from this field.
*/
@Data
public class RefreshTokenRequest {

    @NotBlank
    private String refreshToken;
    
}