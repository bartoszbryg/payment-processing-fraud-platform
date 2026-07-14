package main.service;

import main.databaseModel.User;
import main.dto.response.UserResponse;
import main.exception.ResourceNotFoundException;
import main.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;

    UserService userService;

    @BeforeEach
    void setup() {
        userService = new UserService(userRepository);
    }

    private User activeUser(String id) {
        User u = User.builder()
            .name("Alice Smith").email("alice@example.com")
            .phoneNumber("+14155552671")
            .balance(new BigDecimal("5000.00"))
            .homeCountry("US").homeCity("New York")
            .build();
        u.setId(id);
        return u;
    }

    // getUser

    @Nested
    class GetUserTests {

        @Test
        void getUser_found_returnsResponse() {
            when(userRepository.findById("user-001")).thenReturn(Optional.of(activeUser("user-001")));

            UserResponse response = userService.getUser("user-001");

            assertEquals("user-001", response.getId());
            assertEquals("Alice Smith", response.getName());
            assertEquals("alice@example.com", response.getEmail());
            assertEquals("+1415***2671", response.getPhoneNumber());
            assertEquals(new BigDecimal("5000.00"), response.getBalance());
            assertEquals("US", response.getHomeCountry());
            assertEquals("New York", response.getHomeCity());
            assertTrue(response.isActive());
            assertFalse(response.isFlagged());
        }

        @Test
        void getUser_notFound_throws() {
            when(userRepository.findById("ghost")).thenReturn(Optional.empty());

            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> userService.getUser("ghost"));

            assertTrue(ex.getMessage().contains("ghost"));
        }

        @Test
        void getUser_mapsAllFields() {
            User u = activeUser("user-002");
            u.setRiskScore(55.0);
            u.setFlagged(true);
            u.setEmailVerified(true);
            u.setPhoneVerified(true);
            when(userRepository.findById("user-002")).thenReturn(Optional.of(u));

            UserResponse response = userService.getUser("user-002");

            assertEquals(55.0, response.getRiskScore(), 0.001);
            assertTrue(response.isFlagged());
            assertTrue(response.isEmailVerified());
            assertTrue(response.isPhoneVerified());
        }
    }

    // getAllUsers

    @Nested
    class GetAllUsersTests {

        @Test
        void getAllUsers_returnsMappedPage() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<User> page = new PageImpl<>(List.of(activeUser("u1"), activeUser("u2")));
            when(userRepository.findAll(pageable)).thenReturn(page);

            Page<UserResponse> result = userService.getAllUsers(pageable);

            assertEquals(2, result.getContent().size());
        }

        @Test
        void getAllUsers_emptyPage_returnsEmpty() {
            Pageable pageable = PageRequest.of(0, 10);
            when(userRepository.findAll(pageable)).thenReturn(Page.empty());

            Page<UserResponse> result = userService.getAllUsers(pageable);

            assertTrue(result.getContent().isEmpty());
        }
    }

    // getHighRiskUsers

    @Nested
    class GetHighRiskUsersTests {

        @Test
        void getHighRiskUsers_returnsMatchingUsers() {
            Pageable pageable = PageRequest.of(0, 10);
            User risky = activeUser("risky-001");
            risky.setRiskScore(75.0);
            when(userRepository.findHighRiskUsers(60.0, pageable))
                .thenReturn(new PageImpl<>(List.of(risky)));

            Page<UserResponse> result = userService.getHighRiskUsers(60.0, pageable);

            assertEquals(1, result.getContent().size());
            assertEquals(75.0, result.getContent().get(0).getRiskScore(), 0.001);
        }

        @Test
        void getHighRiskUsers_noneAboveThreshold_returnsEmpty() {
            Pageable pageable = PageRequest.of(0, 10);
            when(userRepository.findHighRiskUsers(90.0, pageable)).thenReturn(Page.empty());

            Page<UserResponse> result = userService.getHighRiskUsers(90.0, pageable);

            assertTrue(result.getContent().isEmpty());
        }
    }

    // getFlaggedUsers

    @Nested
    class GetFlaggedUsersTests {

        @Test
        void getFlaggedUsers_returnsOnlyFlaggedActive() {
            Pageable pageable = PageRequest.of(0, 10);
            User flagged = activeUser("flagged-001");
            flagged.setFlagged(true);
            when(userRepository.findByFlaggedAndActiveTrue(true, pageable))
                .thenReturn(new PageImpl<>(List.of(flagged)));

            Page<UserResponse> result = userService.getFlaggedUsers(pageable);

            assertEquals(1, result.getContent().size());
            assertTrue(result.getContent().get(0).isFlagged());
        }
    }

    // deactivateUser

    @Nested
    class DeactivateUserTests {

        @Test
        void deactivateUser_setsActiveFalse() {
            User user = activeUser("user-001");
            when(userRepository.findById("user-001")).thenReturn(Optional.of(user));
            when(userRepository.save(user)).thenReturn(user);

            UserResponse response = userService.deactivateUser("user-001");

            assertFalse(response.isActive());
            verify(userRepository).save(user);
        }

        @Test
        void deactivateUser_notFound_throws() {
            when(userRepository.findById("ghost")).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                () -> userService.deactivateUser("ghost"));

            verify(userRepository, never()).save(any());
        }

        @Test
        void deactivateUser_persistsChange() {
            User user = activeUser("user-001");
            assertTrue(user.isActive());
            when(userRepository.findById("user-001")).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.deactivateUser("user-001");

            assertFalse(user.isActive()); // entity mutated before save
        }
    }
}