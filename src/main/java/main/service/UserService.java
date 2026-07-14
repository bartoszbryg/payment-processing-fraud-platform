package main.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import main.databaseModel.User;
import main.dto.response.UserResponse;
import main.exception.ResourceNotFoundException;
import main.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
    Read and admin operations on User (bank account holder).
    Account creation lives in AuthService.register() — it must create User + AppUser atomically.
    This service never touches login credentials or passwords.
*/
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserResponse getUser(String userId) {
        return toResponse(userRepository.findById(userId)
            .orElseThrow(() -> ResourceNotFoundException.forUser(userId)));
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> getAllUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(this::toResponse);
    }

    // Active users with riskScore at or above the threshold, worst-first — fraud analyst queue.
    @Transactional(readOnly = true)
    public Page<UserResponse> getHighRiskUsers(double threshold, Pageable pageable) {
        return userRepository.findHighRiskUsers(threshold, pageable).map(this::toResponse);
    }

    // Active flagged accounts — analyst review queue.
    @Transactional(readOnly = true)
    public Page<UserResponse> getFlaggedUsers(Pageable pageable) {
        return userRepository.findByFlaggedAndActiveTrue(true, pageable).map(this::toResponse);
    }

    @Transactional
    public UserResponse deactivateUser(String userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> ResourceNotFoundException.forUser(userId));
        user.setActive(false);
        log.info("User deactivated: id={}", userId);
        return toResponse(userRepository.save(user));
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