package main.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/*
    Single request that creates both a User (bank account) and an AppUser (login credentials)
    atomically in one transaction. If either record fails, both are rolled back.
*/
@Data
@Schema(description = "Customer self-registration — creates a bank account and login in one step")
public class RegisterRequest {

    @NotBlank
    @Schema(description = "Full legal name", example = "Jane Smith")
    private String name;

    @Email
    @NotBlank
    @Schema(description = "Email address - used as login contact and for notifications", example = "jane.smith@example.com")
    private String email;

    @NotBlank
    @Size(min = 3, max = 50)
    @Schema(description = "Chosen username for login", example = "janesmith")
    private String username;

    @NotBlank
    @Size(min = 8, max = 100)
    @Schema(description = "Password - bcrypt-hashed before storage, never logged", example = "S3cur3P@ss!")
    private String password;

    // E.164 format: + followed by 7–15 digits, no spaces or dashes - required by SMS gateways
    @NotBlank
    @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "Phone number must be in E.164 format, e.g. +14155552671")
    @Schema(description = "Mobile number in E.164 format - verified via SMS OTP before account activation", example = "+14155552671")
    private String phoneNumber;

    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 17, fraction = 2)
    @Schema(description = "Initial account balance", example = "10000.00")
    private BigDecimal initialBalance;

    @NotBlank
    @Size(min = 2, max = 10)
    @Schema(description = "ISO country code", example = "US")
    private String homeCountry;

    @Schema(example = "New York")
    private String homeCity;
    
}