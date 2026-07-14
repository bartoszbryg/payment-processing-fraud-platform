package main.service;

import main.databaseModel.AppUser;
import main.databaseModel.User;
import main.dto.request.LoginRequest;
import main.dto.request.RegisterRequest;
import main.dto.response.LoginResponse;
import main.dto.response.UserResponse;
import main.repository.AppUserRepository;
import main.repository.UserRepository;
import main.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock AuthenticationManager authenticationManager;
    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock UserRepository userRepository;
    @Mock AppUserRepository appUserRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock Authentication authentication;

    AuthService authService;

    @BeforeEach
    void setup() {
        authService = new AuthService(
            authenticationManager, jwtTokenProvider,
            userRepository, appUserRepository, passwordEncoder);
    }

    private LoginRequest loginRequest() {
        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("password123");
        return req;
    }

    private RegisterRequest registerRequest() {
        RegisterRequest req = new RegisterRequest();
        req.setName("Alice Smith");
        req.setEmail("alice@example.com");
        req.setUsername("alice");
        req.setPassword("S3cur3P@ss!");
        req.setPhoneNumber("+14155552671");
        req.setInitialBalance(new BigDecimal("10000.00"));
        req.setHomeCountry("US");
        req.setHomeCity("New York");
        return req;
    }

    private User savedUser() {
        User u = User.builder()
            .name("Alice Smith").email("alice@example.com")
            .phoneNumber("+14155552671")
            .balance(new BigDecimal("10000.00"))
            .homeCountry("US").homeCity("New York")
            .build();
        u.setId("user-001");
        return u;
    }

    // Login

    @Nested
    class LoginTests {

        @Test
        void login_success_returnsTokenAndUsername() {
            when(authenticationManager.authenticate(any())).thenReturn(authentication);
            when(authentication.getName()).thenReturn("alice");
            when(jwtTokenProvider.generateToken(authentication)).thenReturn("jwt-token");
            when(jwtTokenProvider.getExpirationSeconds()).thenReturn(86400L);

            LoginResponse response = authService.login(loginRequest());

            assertEquals("jwt-token", response.getAccessToken());
            assertEquals("alice", response.getUsername());
            assertEquals("Bearer", response.getTokenType());
            assertEquals(86400L, response.getAccessTokenExpiresInSeconds());
        }

        @Test
        void login_delegatesCredentialsToAuthenticationManager() {
            when(authenticationManager.authenticate(any())).thenReturn(authentication);
            when(authentication.getName()).thenReturn("alice");
            when(jwtTokenProvider.generateToken(any())).thenReturn("token");

            authService.login(loginRequest());

            ArgumentCaptor<UsernamePasswordAuthenticationToken> captor =
                ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
            verify(authenticationManager).authenticate(captor.capture());

            assertEquals("alice", captor.getValue().getPrincipal());
            assertEquals("password123", captor.getValue().getCredentials());
        }

        @Test
        void login_badCredentials_throws() {
            when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

            assertThrows(BadCredentialsException.class,
                () -> authService.login(loginRequest()));

            verify(jwtTokenProvider, never()).generateToken(any());
        }

        @Test
        void login_noSessionCreated() {
            // Phase 9 — sessions are Phase 10
            when(authenticationManager.authenticate(any())).thenReturn(authentication);
            when(authentication.getName()).thenReturn("alice");
            when(jwtTokenProvider.generateToken(any())).thenReturn("token");

            LoginResponse response = authService.login(loginRequest());

            assertNull(response.getRefreshToken());
            assertNull(response.getSessionId());
        }
    }

    // Register

    @Nested
    class RegisterTests {

        @Test
        void register_success_returnsUserResponse() {
            when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
            when(appUserRepository.existsByUsername("alice")).thenReturn(false);
            when(userRepository.save(any(User.class))).thenReturn(savedUser());
            when(passwordEncoder.encode("S3cur3P@ss!")).thenReturn("hashed-password");

            UserResponse response = authService.register(registerRequest());

            assertEquals("user-001", response.getId());
            assertEquals("Alice Smith", response.getName());
            assertEquals("alice@example.com", response.getEmail());
            assertEquals("+1415***2671", response.getPhoneNumber());
            assertEquals(new BigDecimal("10000.00"), response.getBalance());
            assertEquals("US", response.getHomeCountry());
            assertEquals("New York", response.getHomeCity());
        }

        @Test
        void register_savesUserFirst_thenAppUser() {
            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(appUserRepository.existsByUsername(any())).thenReturn(false);
            when(userRepository.save(any(User.class))).thenReturn(savedUser());
            when(passwordEncoder.encode(any())).thenReturn("hash");

            authService.register(registerRequest());

            // User must be saved before AppUser so linkedUserId exists
            var order = inOrder(userRepository, appUserRepository);
            order.verify(userRepository).save(any(User.class));
            order.verify(appUserRepository).save(any(AppUser.class));
        }

        @Test
        void register_appUserLinkedToUser() {
            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(appUserRepository.existsByUsername(any())).thenReturn(false);
            when(userRepository.save(any())).thenReturn(savedUser());
            when(passwordEncoder.encode(any())).thenReturn("hash");

            authService.register(registerRequest());

            ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
            verify(appUserRepository).save(captor.capture());

            AppUser saved = captor.getValue();
            assertEquals("user-001", saved.getLinkedUserId());
            assertEquals("alice", saved.getUsername());
            assertEquals("hash", saved.getPassword());
            assertEquals("alice@example.com", saved.getEmail());
            assertTrue(saved.getRoles().contains("ROLE_CUSTOMER"));
        }

        @Test
        void register_passwordHashed_neverStoresRaw() {
            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(appUserRepository.existsByUsername(any())).thenReturn(false);
            when(userRepository.save(any())).thenReturn(savedUser());
            when(passwordEncoder.encode("S3cur3P@ss!")).thenReturn("$2a$10$hashed");

            authService.register(registerRequest());

            ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
            verify(appUserRepository).save(captor.capture());
            assertNotEquals("S3cur3P@ss!", captor.getValue().getPassword());
            assertEquals("$2a$10$hashed", captor.getValue().getPassword());
        }

        @Test
        void register_duplicateEmail_throws() {
            when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.register(registerRequest()));

            assertTrue(ex.getMessage().contains("alice@example.com"));
            verify(userRepository, never()).save(any());
            verify(appUserRepository, never()).save(any());
        }

        @Test
        void register_duplicateUsername_throws() {
            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(appUserRepository.existsByUsername("alice")).thenReturn(true);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.register(registerRequest()));

            assertTrue(ex.getMessage().contains("alice"));
            verify(userRepository, never()).save(any());
            verify(appUserRepository, never()).save(any());
        }

        @Test
        void register_customerRoleAssigned() {
            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(appUserRepository.existsByUsername(any())).thenReturn(false);
            when(userRepository.save(any())).thenReturn(savedUser());
            when(passwordEncoder.encode(any())).thenReturn("hash");

            authService.register(registerRequest());

            ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
            verify(appUserRepository).save(captor.capture());
            assertEquals(Set.of("ROLE_CUSTOMER"), captor.getValue().getRoles());
        }
    }
}