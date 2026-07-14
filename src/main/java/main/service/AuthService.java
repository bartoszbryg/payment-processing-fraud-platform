package main.service;

import lombok.RequiredArgsConstructor;
import main.databaseModel.AppUser;
import main.databaseModel.User;
import main.dto.request.LoginRequest;
import main.dto.request.RegisterRequest;
import main.dto.response.LoginResponse;
import main.dto.response.UserResponse;
import main.repository.AppUserRepository;
import main.repository.UserRepository;
import main.security.JwtTokenProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    public LoginResponse login(LoginRequest request) {
        // Throws BadCredentialsException if credentials are wrong — caught by GlobalExceptionHandler.
        // Also runs locked-account check and brute-force protection wired in AppUserDetailsService.
        Authentication auth = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

        String token = jwtTokenProvider.generateToken(auth);

        return LoginResponse.builder()
            .accessToken(token)
            .username(auth.getName())
            .tokenType("Bearer")
            .accessTokenExpiresInSeconds(jwtTokenProvider.getExpirationSeconds())
            .build();
    }

    /*
        Creates a User (bank account) and an AppUser (login credentials) in one transaction.
        If either insert fails — duplicate email, duplicate username, DB error — both roll back.
        This is the only way a customer account comes into existence in the system.
    */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already registered: " + request.getEmail());
        }
        if (appUserRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username already taken: " + request.getUsername());
        }

        // Create the bank account first so we have its ID to link into AppUser
        User user = userRepository.save(User.builder()
            .name(request.getName())
            .email(request.getEmail())
            .phoneNumber(request.getPhoneNumber())
            .balance(request.getInitialBalance())
            .homeCountry(request.getHomeCountry())
            .homeCity(request.getHomeCity())
            .build());

        // Create login credentials, linked to the bank account just created
        appUserRepository.save(AppUser.builder()
            .username(request.getUsername())
            .password(passwordEncoder.encode(request.getPassword()))
            .email(request.getEmail())
            .roles(Set.of("ROLE_CUSTOMER"))
            .linkedUserId(user.getId())
            .build());

        return toResponse(user);
    }

    // Masks middle digits of a phone number: "+14155552671" → "+1415***2671"
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 9) return phone;
        return phone.substring(0, 5) + "***" + phone.substring(phone.length() - 4);
    }

    private UserResponse toResponse(User u) {
        return UserResponse.builder()
            .id(u.getId())
            .name(u.getName())
            .email(u.getEmail())
            .phoneNumber(maskPhone(u.getPhoneNumber()))
            .emailVerified(u.isEmailVerified())
            .phoneVerified(u.isPhoneVerified())
            .balance(u.getBalance())
            .homeCountry(u.getHomeCountry())
            .homeCity(u.getHomeCity())
            .riskScore(u.getRiskScore())
            .active(u.isActive())
            .flagged(u.isFlagged())
            .createdAt(u.getCreatedAt())
            .build();
    }
}