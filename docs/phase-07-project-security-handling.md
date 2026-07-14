# Phase 7 — JWT Security

Every endpoint except login and register must be locked behind a valid token. The wrong option is session cookies because they require server-side state, break horizontal scaling, and are not suited for a REST API consumed by machines. The right option is stateless JWT: the server signs a token at login, the client sends it on every request, the server verifies the signature without any database lookup. That is what I built in Phase 7.

---

## What Was Added

```
├── src/
│   ├── main/java/main/
│   │   └── security/
│   │       ├── JwtTokenProvider.java
│   │       ├── AppUserDetailsService.java
│   │       ├── JwtAuthenticationFilter.java
│   │       └── SecurityConfig.java
│   └── test/java/main/
│       └── security/
│           └── SecurityTest.java
└── src/main/resources/
    └── application-dev.yml       ← jwt.secret added (Base64-encoded 256-bit key)
```

---

## The Four Classes

### `JwtTokenProvider` — `@Component`

Owns everything JWT. Signing key is derived once at startup via `@PostConstruct init()` — `Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret))` — and cached as a field. Every subsequent call reads the cached `SecretKey` directly, never re-deriving it.

```java
@PostConstruct
private void init() {
    this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
}
```

**`generateToken(Authentication)`** — builds a JWT with `sub` (username), `roles` claim (comma-separated), `iat`, `exp`, signs with `signingKey`, returns compact string.

**`validateAndGetUsername(String token)`** — single parse. Either returns the username from the subject claim or returns `null`. Never throws. Internally calls `parseClaims()` inside a try-catch that catches `JwtException` and `IllegalArgumentException` and logs a warning.

```java
public String validateAndGetUsername(String token) {
    try { return parseClaims(token).getSubject(); }
    catch (JwtException | IllegalArgumentException e) {
        log.warn("Invalid JWT token: {}", e.getMessage()); return null;
    }
}
```
---

### `AppUserDetailsService` — `@Service`, implements `UserDetailsService`

Spring Security's database hook. `loadUserByUsername(String username)` queries `AppUserRepository.findByUsername()`, throws `UsernameNotFoundException` if absent, otherwise builds `UserDetails`.

```java
return User.builder()
    .username(appUser.getUsername())
    .password(appUser.getPassword())
    .authorities(appUser.getRoles().stream()
        .map(SimpleGrantedAuthority::new)
        .collect(Collectors.toList()))
    .disabled(!appUser.isActive())
    .build();
```

Roles are stored in the database with the `ROLE_` prefix already included (e.g. `"ROLE_ADMIN"`). `SimpleGrantedAuthority::new` maps them directly — no prefix is added here. Adding one would produce `"ROLE_ROLE_ADMIN"`, which Spring Security's role matchers would never match.

`disabled(!appUser.isActive())` is the only account status flag set. `accountExpired` is deliberately omitted because account expiry is a credential-lifetime concept (password rotation), not an admin deactivation concept. Setting both would be semantically wrong and redundant since `DaoAuthenticationProvider` checks `isEnabled()` first anyway.

---

### `JwtAuthenticationFilter` — extends `OncePerRequestFilter`, no `@Component`

Runs once per request. Not annotated with `@Component` because Spring Boot auto-registers any `@Component` filter at the servlet level outside `FilterChainProxy`. The filter is registered exclusively inside the security filter chain via `SecurityConfig`.

**`doFilterInternal` flow:**

```
extractToken(request)
  → validateAndGetUsername(token)       // null if missing/invalid
  → if username != null AND context has no auth:
      loadUserByUsername(username)
      if !userDetails.isEnabled(): warn and skip
      else: setAuthentication(UsernamePasswordAuthenticationToken)
filterChain.doFilter(request, response) // always called — never inside try-catch
```

`UsernameNotFoundException` is caught explicitly. If a user is deleted after their token was issued, the token still passes JJWT signature validation but the DB lookup fails. The exception is caught, a warning is logged, and the filter falls through with an empty `SecurityContext` so the request proceeds unauthenticated and gets a 401 from `AuthorizationFilter` downstream.

`filterChain.doFilter()` is placed outside every try-catch block to guarantee the chain always continues regardless of outcome.

---

### `SecurityConfig` — `@Configuration @EnableWebSecurity @EnableMethodSecurity`

Wires everything together. Produces six beans.

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(e -> e
            .authenticationEntryPoint((req, res, ex) ->
                res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/auth/login", "/auth/register").permitAll()
            .requestMatchers("/h2-console/**").permitAll()
            .requestMatchers("/actuator/health").permitAll()
            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
            .anyRequest().authenticated())
        .headers(h -> h.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin))
        .authenticationProvider(authenticationProvider())
        .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);
    return http.build();
}
```

`FilterRegistrationBean` is required to prevent a double-registration bug:

```java
@Bean
public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration() {
    FilterRegistrationBean<JwtAuthenticationFilter> reg =
        new FilterRegistrationBean<>(jwtAuthenticationFilter());
    reg.setEnabled(false);
    return reg;
}
```

Without `setEnabled(false)`, Spring Boot detects the `@Bean` filter and registers it at the servlet container level **outside** `FilterChainProxy`. It runs once before Spring Security, sets authentication. Then inside `FilterChainProxy`, `SecurityContextHolderFilter` (STATELESS mode uses `NullSecurityContextRepository`) clears the `SecurityContext`. `OncePerRequestFilter`'s already-filtered tracking then skips the filter inside the chain. Result: every protected endpoint returns 401 permanently.

**Bean inventory:**

| Bean | Purpose |
|---|---|
| `SecurityFilterChain` | Immutable rule set: matchers + ordered filter list |
| `JwtAuthenticationFilter` | The filter instance itself |
| `FilterRegistrationBean` (disabled) | Prevents servlet-level double-registration |
| `DaoAuthenticationProvider` | Verifies credentials: `loadUserByUsername` + `passwordEncoder.matches()` |
| `AuthenticationManager` | Exposed so `AuthController` can call `authenticate()` directly |
| `BCryptPasswordEncoder` | Shared across registration and `DaoAuthenticationProvider` |

---

## Three Objects You Need to Understand

**`HttpSecurity`** is a builder. Spring passes it into `filterChain()`. You call methods on it to register configurers and filters. `http.build()` materialises all of them into a `SecurityFilterChain` and discards `HttpSecurity`.

**`SecurityFilterChain`** is immutable once built. It holds a request matcher (which requests it handles) and an ordered list of filters. It lives in the application context for the lifetime of the process.

**`FilterChainProxy`** is registered with Tomcat by Spring Security's auto-configuration. It is a single servlet `Filter`. On every request it finds the matching `SecurityFilterChain`, creates a `VirtualFilterChain`, and runs the filters in order. It owns the actual execution.

---

## Startup Sequence

```
BCryptPasswordEncoder
  → DaoAuthenticationProvider (uses PasswordEncoder + UserDetailsService)
  → AuthenticationManager (wraps DaoAuthenticationProvider)
  → JwtAuthenticationFilter (requires JwtTokenProvider + UserDetailsService)
  → FilterRegistrationBean (holds JwtAuthenticationFilter, disabled)
  → SecurityFilterChain (HttpSecurity.build(): all configurers wired in)
  → JwtTokenProvider @PostConstruct (derives SecretKey from jwt.secret)
  → FilterChainProxy (Spring Security auto-config picks up SecurityFilterChain)
```

---

## Filter Chain Per Request

```
Tomcat → FilterChainProxy
  → SecurityContextHolderFilter
  → HeaderWriterFilter
  → LogoutFilter
  → JwtAuthenticationFilter          ← sets Authentication if token valid
  → AnonymousAuthenticationFilter    ← sets anonymous if still empty
  → ExceptionTranslationFilter       ← converts AccessDeniedException → response
  → AuthorizationFilter              ← enforces .anyRequest().authenticated()
  → DispatcherServlet → controller
← Response unwinds in reverse order
```

`HttpServletResponse` is a mutable output stream. It is written into during the response phase and never returned. The reverse-order unwinding is the filter's `doFilter()` returning back up the call stack, not a second pass.

---

## Login Flow

`JwtAuthenticationFilter` has no token to validate on a login request so it falls straight through. The controller must do the work explicitly:

```java
Authentication authentication = authenticationManager.authenticate(
    new UsernamePasswordAuthenticationToken(username, password)
);
String token = jwtTokenProvider.generateToken(authentication);
return new LoginResponse(token, ...);
```

`authenticationManager.authenticate()` delegates to `DaoAuthenticationProvider`:
1. `loadUserByUsername(username)` — fetches user from DB
2. `passwordEncoder.matches(rawPassword, storedHash)` — verifies password

Both wrong username and wrong password throw `BadCredentialsException`. They are deliberately indistinguishable to prevent username enumeration attacks. `GlobalExceptionHandler.handleBadCredentials()` maps this to a 401 response.

---

## API Request Flow

```
Request with "Authorization: Bearer <token>"
  → JwtAuthenticationFilter
      extractToken()               → "eyJ..."
      validateAndGetUsername()     → "alice"  (or null → skip)
      loadUserByUsername("alice")  → UserDetails
      isEnabled() check            → pass
      setAuthentication()          → SecurityContext populated
  → AuthorizationFilter            → .anyRequest().authenticated() → passes
  → DispatcherServlet → controller → business logic
```

---

## Error Paths

| Scenario | Who handles it | Response |
|---|---|---|
| No token / invalid token | Spring Security `AuthenticationEntryPoint` | 401 |
| Deleted user (valid token) | `JwtAuthenticationFilter` catches `UsernameNotFoundException` | 401 |
| Disabled user (valid token) | `JwtAuthenticationFilter` `isEnabled()` check | 401 |
| Bad credentials at login | `GlobalExceptionHandler.handleBadCredentials()` | 401 |
| Wrong role (authenticated) | `GlobalExceptionHandler.handleAccessDenied()` | 403 |

**Important distinction:** unauthenticated requests (no/invalid token) never reach `GlobalExceptionHandler`. They are intercepted by `ExceptionTranslationFilter` and routed to the custom `AuthenticationEntryPoint`, which calls `res.sendError(401)` directly. Spring Security 6 defaults to `Http403ForbiddenEntryPoint` when no entry point is configured, which means unauthenticated requests return 403, not 401. The explicit entry point in `SecurityConfig` is required.

---

## Decisions and Why

| Decision | Reason |
|---|---|
| CSRF disabled | Bearer token in `Authorization` header is not vulnerable to CSRF. Cookies are. |
| STATELESS session | `NullSecurityContextRepository` — nothing is stored server-side between requests. ThreadLocal only. |
| `FilterRegistrationBean.setEnabled(false)` | Prevents double-registration bug: filter running outside `FilterChainProxy` → auth cleared by `SecurityContextHolderFilter` → 401 on everything. |
| `isEnabled()` check in filter | A deactivated account with a valid unexpired token must still be blocked. |
| `validateAndGetUsername` merges two methods | Single parse per request. `validateToken()` + `getUsernameFromToken()` both called `parseClaims()` — that was two JJWT verifications per request. |
| `@PostConstruct` for `signingKey` | Key derivation happens once at startup, not on every token operation. |
| Same `BadCredentialsException` for both failures | Username enumeration prevention — attacker cannot distinguish "user not found" from "wrong password". |
| Custom `AuthenticationEntryPoint` | Spring Security 6 sends 403 by default for unauthenticated — REST APIs must return 401. |
| `SimpleGrantedAuthority::new` (no prefix) | Roles stored as `"ROLE_ADMIN"` in DB. Adding `"ROLE_"` prefix here produces `"ROLE_ROLE_ADMIN"`. |
| No `accountExpired` | `disabled()` is the correct semantic for admin deactivation. `accountExpired` is for credential lifetime (password rotation). |

---

## `application-dev.yml` — JWT Config

```yaml
jwt:
  # Base64-encoded 256-bit key (32 bytes). NEVER use this in production.
  secret: ZGV2LWZyYXVkLWRldGVjdGlvbi1zZWNyZXQta2V5LTMyYg==
  expiration-seconds: 86400
```

JJWT requires the secret to be a **Base64-encoded** string that decodes to at least 256 bits (32 bytes) for HMAC-SHA256. A plain text string fails with `WeakKeyException` at startup. The value above decodes to exactly 35 bytes (280 bits).

---

## Test Suite — `SecurityTest.java`

Four `@Nested` classes, 40 tests total.

### 1. `JwtTokenProviderTests` — pure unit, no Spring context

Uses `ReflectionTestUtils.setField()` to inject `@Value` fields and `ReflectionTestUtils.invokeMethod()` to call the `private` `@PostConstruct init()`.

| Test | Asserts |
|---|---|
| `generateToken_returnsWellFormedJwt` | Non-null, exactly 3 dot-separated parts |
| `validateAndGetUsername_validToken_returnsUsername` | Returns correct subject |
| `generateAndValidate_multipleRoles` | Multiple roles encoded without error |
| `validateAndGetUsername_tamperedToken_returnsNull` | Returns null |
| `validateAndGetUsername_expiredToken_returnsNull` | Returns null |
| `validateAndGetUsername_garbageString_returnsNull` | Returns null |
| `validateAndGetUsername_wrongKey_returnsNull` | Token signed with `OTHER_SECRET` rejected |
| `getExpirationSeconds_returnsConfiguredValue` | Returns injected value |
| `init_withTooShortKey_throws` | `WeakKeyException` on 5-byte key |
| `signingKey_isCachedNotRecomputed` | Two tokens from same provider both valid |

### 2. `AppUserDetailsServiceTests` — `@Mock AppUserRepository`

`@ExtendWith(MockitoExtension.class)`, `@InjectMocks AppUserDetailsService`.

| Test | Asserts |
|---|---|
| `loadByUsername_activeUser_returnsEnabledUserDetails` | `isEnabled()` true, correct username and password |
| `loadByUsername_inactiveUser_returnsDisabledUserDetails` | `isEnabled()` false |
| `loadByUsername_unknownUser_throwsUsernameNotFoundException` | Exception thrown |
| `loadByUsername_roles_notDoublePrefixed` | `ROLE_ADMIN` present, `ROLE_ROLE_ADMIN` absent |
| `loadByUsername_multipleRoles_allMapped` | 3 roles → 3 authorities |
| `loadByUsername_inactiveUser_accountExpiredNotSet` | `isAccountNonExpired()` true (only `disabled` set) |

### 3. `JwtAuthenticationFilterTests` — `@Mock` all deps

`@ExtendWith(MockitoExtension.class)`. `SecurityContextHolder.clearContext()` in `@BeforeEach` and `@AfterEach`. Filter instantiated directly via constructor.

| Test | Asserts |
|---|---|
| `noHeader_contextEmpty_chainContinues` | Null auth, chain called |
| `basicAuthHeader_ignored` | `Basic` scheme ignored, chain called |
| `emptyBearerValue_skipsValidation` | `Bearer ` with no token — `validateAndGetUsername` never called |
| `invalidToken_contextEmpty_chainContinues` | Provider returns null → context stays empty |
| `validToken_activeUser_authenticationSet` | `Authentication` set, `getName()` = "alice" |
| `validToken_disabledUser_contextEmpty` | `isEnabled()` false → context stays empty |
| `validToken_deletedUser_contextEmpty` | `UsernameNotFoundException` caught → context stays empty |
| `preExistingAuth_notOverwritten` | Upstream auth untouched, `loadUserByUsername` never called |
| `filterChainAlwaysCalled` | `verify(filterChain, times(1)).doFilter(...)` regardless of outcome |

### 4. `SecurityFilterChainTests` — full Spring context

`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("dev") @Transactional`. Real H2 database, real filter chain. `@BeforeEach` seeds one `AppUser` via `appUserRepository.saveAndFlush()`. `@Transactional` rolls back after each test.

`tokenFor(username, roles...)` builds a signed token without touching the database. `expiredToken(username)` uses `ReflectionTestUtils.getField(jwtTokenProvider, "signingKey")` to build a correctly signed but already-expired token.

| Test | Asserts |
|---|---|
| `authLogin_permittedWithoutToken` | Status ≠ 401 |
| `authRegister_permittedWithoutToken` | Status ≠ 401 |
| `actuatorHealth_publicNoTokenRequired` | Status 200 |
| `openApiDocs_notBlockedBySecurity` | Status ≠ 401 |
| `protectedEndpoint_noToken_returns401` | Status 401 |
| `protectedEndpoint_validToken_returns200` | Status 200 |
| `protectedEndpoint_expiredToken_returns401` | Status 401 |
| `protectedEndpoint_tamperedToken_returns401` | Status 401 |
| `protectedEndpoint_garbageToken_returns401` | Status 401 |
| `protectedEndpoint_emptyBearer_returns401` | Status 401 |
| `protectedEndpoint_basicAuthScheme_returns401` | Status 401 |
| `protectedEndpoint_tokenForNonExistentUser_returns401` | Status 401 |
| `protectedEndpoint_userDeletedAfterTokenIssued_returns401` | Status 401 |
| `protectedEndpoint_deactivatedUser_returns401` | Status 401 |
| `protectedEndpoint_tokenSignedWithWrongSecret_returns401` | Status 401 |

Protected endpoint used: `/actuator/metrics` (requires authentication, exposed in dev profile).

---

## Actual Test Output

```
[INFO] Running main.security.SecurityTest

WARN  main.security.JwtTokenProvider - Invalid JWT token: JWT expired at ...
WARN  main.security.JwtTokenProvider - Invalid JWT token: JWT signature does not match ...
WARN  main.security.JwtTokenProvider - Invalid JWT token: Unable to read bytes ...
WARN  main.security.JwtAuthenticationFilter - Token valid but user no longer exists: ...
WARN  main.security.JwtAuthenticationFilter - Token valid but account is disabled: testuser

[INFO] Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

The `WARN` lines are the security code working correctly — the tests are deliberately exercising the invalid-token and deleted-user paths, so the warnings are expected and intentional. They are not failures.

---

## How to Run

```powershell
# Full security suite
mvn test -Dtest="SecurityTest"

# Individual nested class
mvn test -Dtest="SecurityTest\$JwtTokenProviderTests"
mvn test -Dtest="SecurityTest\$AppUserDetailsServiceTests"
mvn test -Dtest="SecurityTest\$JwtAuthenticationFilterTests"
mvn test -Dtest="SecurityTest\$SecurityFilterChainTests"

# Single test method
mvn test -Dtest="SecurityTest\$JwtTokenProviderTests#validateAndGetUsername_expiredToken_returnsNull"

# All project tests
mvn test
```

---

## What Forces the Next Step

Security is wired. The filter chain validates JWTs and enforces authentication on every protected endpoint. But there is nothing behind those endpoints yet. Next I will build the fraud detection engine which is the core product, covering the rule interface, individual rule implementations, graph-based detection, and the orchestrator that scores every transaction. After that I will wire up the Auth Controller so the login and register endpoints actually work end to end.