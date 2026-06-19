package main.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import main.databaseModel.AppUser;
import main.repository.AppUserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full security test suite — four nested sections, one per security class:
 *   1. JwtTokenProvider — pure unit (no Spring context)
 *   2. AppUserDetailsService — unit with mocked repository
 *   3. JwtAuthenticationFilter — unit, filter invoked directly
 *   4. SecurityFilterChain — integration (@SpringBootTest, real HTTP flow via MockMvc)
 */
class SecurityTest {

    // Matches application-dev.yml jwt.secret (Base64-encoded, decodes to 35 bytes = 280 bits)
    private static final String TEST_SECRET = "ZGV2LWZyYXVkLWRldGVjdGlvbi1zZWNyZXQta2V5LTMyYg==";
    private static final long   TEST_EXPIRY = 86400L;

    // A different valid 256-bit secret (Base64 of "other-test-secret-key-32-bytes!!" = 32 bytes)
    private static final String OTHER_SECRET = "b3RoZXItdGVzdC1zZWNyZXQta2V5LTMyLWJ5dGVzISE=";


    // 1. JwtTokenProvider
    @Nested
    @DisplayName("JwtTokenProvider")
    class JwtTokenProviderTests {

        private JwtTokenProvider tokenProvider;

        @BeforeEach
        void setUp() {
            tokenProvider = new JwtTokenProvider();
            ReflectionTestUtils.setField(tokenProvider, "jwtSecret", TEST_SECRET);
            ReflectionTestUtils.setField(tokenProvider, "expirationSeconds", TEST_EXPIRY);
            ReflectionTestUtils.invokeMethod(tokenProvider, "init");
        }

        private Authentication auth(String username, String... roles) {
            var authorities = Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
            return new UsernamePasswordAuthenticationToken(username, null, authorities);
        }

        @Test
        @DisplayName("generateToken — returns a non-blank three-part JWT")
        void generateToken_returnsWellFormedJwt() {
            String token = tokenProvider.generateToken(auth("alice", "ROLE_USER"));
            assertNotNull(token);
            assertEquals(3, token.split("\\.").length, "JWT must have header.payload.signature");
        }

        @Test
        @DisplayName("validateAndGetUsername — valid token returns correct subject")
        void validateAndGetUsername_validToken_returnsUsername() {
            String token = tokenProvider.generateToken(auth("alice", "ROLE_USER"));
            assertEquals("alice", tokenProvider.validateAndGetUsername(token));
        }

        @Test
        @DisplayName("validateAndGetUsername — multiple roles encoded without error")
        void generateAndValidate_multipleRoles() {
            String token = tokenProvider.generateToken(auth("admin", "ROLE_ADMIN", "ROLE_USER"));
            assertEquals("admin", tokenProvider.validateAndGetUsername(token));
        }

        @Test
        @DisplayName("validateAndGetUsername — tampered signature returns null")
        void validateAndGetUsername_tamperedToken_returnsNull() {
            String token = tokenProvider.generateToken(auth("alice", "ROLE_USER"));
            String tampered = token.substring(0, token.length() - 4) + "XXXX";
            assertNull(tokenProvider.validateAndGetUsername(tampered));
        }

        @Test
        @DisplayName("validateAndGetUsername — expired token returns null")
        void validateAndGetUsername_expiredToken_returnsNull() {
            SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_SECRET));
            String expired = Jwts.builder()
                .subject("alice")
                .issuedAt(new Date(System.currentTimeMillis() - 10_000))
                .expiration(new Date(System.currentTimeMillis() - 1_000))
                .signWith(key)
                .compact();
            assertNull(tokenProvider.validateAndGetUsername(expired));
        }

        @Test
        @DisplayName("validateAndGetUsername — garbage string returns null")
        void validateAndGetUsername_garbageString_returnsNull() {
            assertNull(tokenProvider.validateAndGetUsername("not.a.jwt.at.all"));
        }

        @Test
        @DisplayName("validateAndGetUsername — token signed with different key returns null")
        void validateAndGetUsername_wrongKey_returnsNull() {
            SecretKey otherKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(OTHER_SECRET));
            String foreignToken = Jwts.builder()
                .subject("alice")
                .expiration(new Date(System.currentTimeMillis() + 86_400_000))
                .signWith(otherKey)
                .compact();
            assertNull(tokenProvider.validateAndGetUsername(foreignToken));
        }

        @Test
        @DisplayName("getExpirationSeconds — returns the configured value")
        void getExpirationSeconds_returnsConfiguredValue() {
            assertEquals(TEST_EXPIRY, tokenProvider.getExpirationSeconds());
        }

        @Test
        @DisplayName("@PostConstruct init — too-short key throws WeakKeyException at startup")
        void init_withTooShortKey_throws() {
            JwtTokenProvider bad = new JwtTokenProvider();
            // Base64 of "short" = only 5 decoded bytes — below HMAC-SHA256 minimum of 32
            ReflectionTestUtils.setField(bad, "jwtSecret", "c2hvcnQ=");
            ReflectionTestUtils.setField(bad, "expirationSeconds", 86400L);
            assertThrows(Exception.class, () -> ReflectionTestUtils.invokeMethod(bad, "init"));
        }

        @Test
        @DisplayName("signingKey is cached — both tokens valid after multiple generateToken calls")
        void signingKey_isCachedNotRecomputed() {
            String t1 = tokenProvider.generateToken(auth("u1", "ROLE_USER"));
            String t2 = tokenProvider.generateToken(auth("u2", "ROLE_USER"));
            assertEquals("u1", tokenProvider.validateAndGetUsername(t1));
            assertEquals("u2", tokenProvider.validateAndGetUsername(t2));
        }
    }

    // 2. AppUserDetailsService
    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("AppUserDetailsService")
    class AppUserDetailsServiceTests {

        @Mock AppUserRepository appUserRepository;
        @InjectMocks AppUserDetailsService userDetailsService;

        private AppUser user(String username, boolean active, String... roles) {
            return AppUser.builder()
                .id("u1").username(username).password("$2a$10$hashed")
                .email(username + "@bank.internal")
                .roles(new HashSet<>(Arrays.asList(roles)))
                .active(active).build();
        }

        @Test
        @DisplayName("active user — UserDetails is enabled with correct fields")
        void loadByUsername_activeUser_returnsEnabledUserDetails() {
            when(appUserRepository.findByUsername("alice"))
                .thenReturn(Optional.of(user("alice", true, "ROLE_USER")));

            UserDetails ud = userDetailsService.loadUserByUsername("alice");

            assertEquals("alice", ud.getUsername());
            assertEquals("$2a$10$hashed", ud.getPassword());
            assertTrue(ud.isEnabled());
            assertFalse(ud.getAuthorities().isEmpty());
        }

        @Test
        @DisplayName("inactive user — UserDetails is disabled")
        void loadByUsername_inactiveUser_returnsDisabledUserDetails() {
            when(appUserRepository.findByUsername("alice"))
                .thenReturn(Optional.of(user("alice", false, "ROLE_USER")));

            UserDetails ud = userDetailsService.loadUserByUsername("alice");

            assertFalse(ud.isEnabled());
        }

        @Test
        @DisplayName("unknown username — throws UsernameNotFoundException")
        void loadByUsername_unknownUser_throwsUsernameNotFoundException() {
            when(appUserRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThrows(UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername("ghost"));
        }

        @Test
        @DisplayName("roles stored as ROLE_X — no double prefix produced")
        void loadByUsername_roles_notDoublePrefixed() {
            // Stored as "ROLE_ADMIN" — must produce "ROLE_ADMIN", not "ROLE_ROLE_ADMIN"
            when(appUserRepository.findByUsername("admin"))
                .thenReturn(Optional.of(user("admin", true, "ROLE_ADMIN", "ROLE_USER")));

            var authorities = userDetailsService.loadUserByUsername("admin")
                .getAuthorities().stream().map(a -> a.getAuthority()).toList();

            assertTrue(authorities.contains("ROLE_ADMIN"));
            assertTrue(authorities.contains("ROLE_USER"));
            assertFalse(authorities.stream().anyMatch(a -> a.startsWith("ROLE_ROLE_")));
        }

        @Test
        @DisplayName("multiple roles — all mapped to authorities")
        void loadByUsername_multipleRoles_allMapped() {
            when(appUserRepository.findByUsername("multi"))
                .thenReturn(Optional.of(user("multi", true, "ROLE_ADMIN", "ROLE_USER", "ROLE_ANALYST")));

            assertEquals(3, userDetailsService.loadUserByUsername("multi").getAuthorities().size());
        }

        @Test
        @DisplayName("inactive user — accountExpired NOT set (disabled is the right semantic)")
        void loadByUsername_inactiveUser_accountExpiredNotSet() {
            when(appUserRepository.findByUsername("alice"))
                .thenReturn(Optional.of(user("alice", false, "ROLE_USER")));

            UserDetails ud = userDetailsService.loadUserByUsername("alice");

            assertFalse(ud.isEnabled());
            // soft-deactivation uses disabled(); accountExpired is for credential lifetime,
            // which is a different concept from an admin deactivating an account
            assertTrue(ud.isAccountNonExpired());
        }
    }

    // 3. JwtAuthenticationFilter
    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("JwtAuthenticationFilter")
    class JwtAuthenticationFilterTests {

        @Mock JwtTokenProvider     tokenProvider;
        @Mock UserDetailsService   userDetailsService;
        @Mock HttpServletRequest   request;
        @Mock HttpServletResponse  response;
        @Mock FilterChain          filterChain;

        private JwtAuthenticationFilter filter;

        @BeforeEach
        void setUp() {
            filter = new JwtAuthenticationFilter(tokenProvider, userDetailsService);
            SecurityContextHolder.clearContext();
        }

        @AfterEach
        void tearDown() {
            SecurityContextHolder.clearContext();
        }

        private UserDetails enabledUser(String username) {
            return User.builder().username(username).password("hash")
                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                .disabled(false).build();
        }

        private UserDetails disabledUser(String username) {
            return User.builder().username(username).password("hash")
                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                .disabled(true).build();
        }

        @Test
        @DisplayName("no Authorization header — context stays empty, chain continues")
        void noHeader_contextEmpty_chainContinues() throws Exception {
            when(request.getHeader("Authorization")).thenReturn(null);

            filter.doFilterInternal(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Authorization header without Bearer prefix — ignored")
        void basicAuthHeader_ignored() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

            filter.doFilterInternal(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("invalid token (provider returns null) — context stays empty")
        void invalidToken_contextEmpty_chainContinues() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer bad.token");
            when(tokenProvider.validateAndGetUsername("bad.token")).thenReturn(null);

            filter.doFilterInternal(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("valid token + active user — Authentication set in SecurityContext")
        void validToken_activeUser_authenticationSet() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer valid.token");
            when(tokenProvider.validateAndGetUsername("valid.token")).thenReturn("alice");
            when(userDetailsService.loadUserByUsername("alice")).thenReturn(enabledUser("alice"));

            filter.doFilterInternal(request, response, filterChain);

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(auth);
            assertEquals("alice", auth.getName());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("valid token + disabled user — context stays empty (deactivated account blocked)")
        void validToken_disabledUser_contextEmpty() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer valid.token");
            when(tokenProvider.validateAndGetUsername("valid.token")).thenReturn("alice");
            when(userDetailsService.loadUserByUsername("alice")).thenReturn(disabledUser("alice"));

            filter.doFilterInternal(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("valid token + deleted user — UsernameNotFoundException caught, context stays empty")
        void validToken_deletedUser_contextEmpty() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer valid.token");
            when(tokenProvider.validateAndGetUsername("valid.token")).thenReturn("ghost");
            when(userDetailsService.loadUserByUsername("ghost"))
                .thenThrow(new UsernameNotFoundException("User not found: ghost"));

            filter.doFilterInternal(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("pre-existing authentication — not overwritten, userDetailsService never called")
        void preExistingAuth_notOverwritten() throws Exception {
            var existing = new UsernamePasswordAuthenticationToken("upstream", null, List.of());
            SecurityContextHolder.getContext().setAuthentication(existing);

            when(request.getHeader("Authorization")).thenReturn("Bearer valid.token");
            when(tokenProvider.validateAndGetUsername("valid.token")).thenReturn("alice");

            filter.doFilterInternal(request, response, filterChain);

            assertEquals("upstream", SecurityContextHolder.getContext().getAuthentication().getName());
            verify(userDetailsService, never()).loadUserByUsername(any());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("filterChain.doFilter is always called regardless of outcome")
        void filterChainAlwaysCalled() throws Exception {
            when(request.getHeader("Authorization")).thenReturn(null);

            filter.doFilterInternal(request, response, filterChain);

            verify(filterChain, times(1)).doFilter(request, response);
        }

        @Test
        @DisplayName("empty Bearer value — validateAndGetUsername never called, context stays empty")
        void emptyBearerValue_skipsValidation() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer ");

            filter.doFilterInternal(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(tokenProvider, never()).validateAndGetUsername(any());
            verify(filterChain).doFilter(request, response);
        }
    }

    // 4. SecurityFilterChain — integration tests (real Spring context + H2)
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("dev")
    @Transactional
    @DisplayName("SecurityFilterChain integration")
    class SecurityFilterChainTests {

        @Autowired MockMvc           mvc;
        @Autowired JwtTokenProvider  jwtTokenProvider;
        @Autowired AppUserRepository appUserRepository;
        @Autowired PasswordEncoder   passwordEncoder;

        private AppUser testUser;

        @BeforeEach
        void seedUser() {
            testUser = appUserRepository.saveAndFlush(AppUser.builder()
                .username("testuser")
                .email("testuser@sectest.internal")
                .password(passwordEncoder.encode("password123"))
                // HashSet — mutable so Hibernate doesn't wrap an immutable Set.of()
                .roles(new HashSet<>(Set.of("ROLE_USER")))
                .active(true)
                .build());
        }

        /** Produces a signed token without touching the database. */
        private String tokenFor(String username, String... roles) {
            var authorities = Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
            return jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(username, null, authorities));
        }

        /** Builds an already-expired token using the live signing key. */
        private String expiredToken(String username) {
            SecretKey key = (SecretKey) ReflectionTestUtils.getField(jwtTokenProvider, "signingKey");
            return Jwts.builder()
                .subject(username)
                .expiration(new Date(System.currentTimeMillis() - 1_000))
                .signWith(key)
                .compact();
        }

        // Public endpoints

        @Test
        @DisplayName("/auth/login — permitted by security (not blocked with 401)")
        void authLogin_permittedWithoutToken() throws Exception {
            // No controller for /auth/login yet — security must pass it through (≠ 401)
            mvc.perform(get("/auth/login"))
                .andExpect(result ->
                    assertNotEquals(401, result.getResponse().getStatus(),
                        "/auth/login must not be blocked by Spring Security"));
        }

        @Test
        @DisplayName("/auth/register — permitted by security (not blocked with 401)")
        void authRegister_permittedWithoutToken() throws Exception {
            mvc.perform(get("/auth/register"))
                .andExpect(result ->
                    assertNotEquals(401, result.getResponse().getStatus(),
                        "/auth/register must not be blocked by Spring Security"));
        }

        @Test
        @DisplayName("/actuator/health — public, returns 200 without token")
        void actuatorHealth_publicNoTokenRequired() throws Exception {
            mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("/v3/api-docs — permitted by security without token")
        void openApiDocs_notBlockedBySecurity() throws Exception {
            mvc.perform(get("/v3/api-docs"))
                .andExpect(result ->
                    assertNotEquals(401, result.getResponse().getStatus(),
                        "/v3/api-docs must not be blocked by Spring Security"));
        }

        // Protected endpoints
        @Test
        @DisplayName("protected endpoint — no token returns 401")
        void protectedEndpoint_noToken_returns401() throws Exception {
            mvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("protected endpoint — valid token for existing active user returns 200")
        void protectedEndpoint_validToken_returns200() throws Exception {
            String token = tokenFor("testuser", "ROLE_USER");

            mvc.perform(get("/actuator/metrics")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("protected endpoint — expired token returns 401")
        void protectedEndpoint_expiredToken_returns401() throws Exception {
            mvc.perform(get("/actuator/metrics")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken("testuser")))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("protected endpoint — tampered signature returns 401")
        void protectedEndpoint_tamperedToken_returns401() throws Exception {
            String token    = tokenFor("testuser", "ROLE_USER");
            String tampered = token.substring(0, token.length() - 4) + "XXXX";

            mvc.perform(get("/actuator/metrics")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("protected endpoint — garbage token returns 401")
        void protectedEndpoint_garbageToken_returns401() throws Exception {
            mvc.perform(get("/actuator/metrics")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer not-even-close"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("protected endpoint — empty Bearer value returns 401")
        void protectedEndpoint_emptyBearer_returns401() throws Exception {
            mvc.perform(get("/actuator/metrics")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer "))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("protected endpoint — non-Bearer scheme (Basic) returns 401")
        void protectedEndpoint_basicAuthScheme_returns401() throws Exception {
            mvc.perform(get("/actuator/metrics")
                    .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("protected endpoint — token for user never in DB returns 401")
        void protectedEndpoint_tokenForNonExistentUser_returns401() throws Exception {
            String token = tokenFor("ghost-user-never-existed", "ROLE_USER");

            mvc.perform(get("/actuator/metrics")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("protected endpoint — user deleted after token issued returns 401")
        void protectedEndpoint_userDeletedAfterTokenIssued_returns401() throws Exception {
            String token = tokenFor("testuser", "ROLE_USER");

            appUserRepository.delete(testUser);
            appUserRepository.flush();

            mvc.perform(get("/actuator/metrics")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("protected endpoint — deactivated user's token returns 401")
        void protectedEndpoint_deactivatedUser_returns401() throws Exception {
            String token = tokenFor("testuser", "ROLE_USER");

            // Reload to get a Hibernate-managed entity, then deactivate
            AppUser managed = appUserRepository.findById(testUser.getId()).orElseThrow();
            managed.setActive(false);
            appUserRepository.saveAndFlush(managed);

            mvc.perform(get("/actuator/metrics")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("protected endpoint — token signed with wrong secret returns 401")
        void protectedEndpoint_tokenSignedWithWrongSecret_returns401() throws Exception {
            // "wrong-secret-for-integration-test" = 33 bytes, a valid key size but different secret
            SecretKey wrongKey = Keys.hmacShaKeyFor(
                Decoders.BASE64.decode("d3Jvbmctc2VjcmV0LWZvci1pbnRlZ3JhdGlvbi10ZXN0"));
            String foreignToken = Jwts.builder()
                .subject("testuser")
                .expiration(new Date(System.currentTimeMillis() + 86_400_000))
                .signWith(wrongKey)
                .compact();

            mvc.perform(get("/actuator/metrics")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + foreignToken))
                .andExpect(status().isUnauthorized());
        }
    }
}