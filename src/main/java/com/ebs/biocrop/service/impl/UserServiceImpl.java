package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.UserProfileUpdateRequest;
import com.ebs.biocrop.dto.response.UserProfileResponse;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserProfileResponse getProfileByPhoneNumber(String phoneNumber) {
        User user = findByIdentifier(phoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("User", "phoneNumber", phoneNumber));

        if (Boolean.TRUE.equals(user.getIsDeleted())) {
            throw new ResourceNotFoundException("User profile not found or has been deleted");
        }

        return mapToResponse(user);
    }

    @Override
    public UserProfileResponse updateProfile(String phoneNumber, UserProfileUpdateRequest request) {
        User user = findByIdentifier(phoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("User", "phoneNumber", phoneNumber));

        if (Boolean.TRUE.equals(user.getIsDeleted())) {
            throw new ResourceNotFoundException("User profile not found or has been deleted");
        }

        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setAddress(request.getAddress());
        boolean profileComplete = user.hasCompleteProfile();
        if (!profileComplete) {
            throw new AppException("Complete all required profile fields and provide a valid 6-digit pincode", HttpStatus.BAD_REQUEST);
        }
        user.setIsProfileComplete(profileComplete);
        // Profile updates require an authenticated token issued after OTP verification.
        user.setIsVerified(true);
        user.setUpdatedAt(LocalDateTime.now());

        User savedUser = userRepository.save(user);
        return mapToResponse(savedUser);
    }

    @Override
    public UserProfileResponse softDeleteUserProfile(String phoneNumber) {
        User user = findByIdentifier(phoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("User", "phoneNumber", phoneNumber));

        if (Boolean.TRUE.equals(user.getIsDeleted())) {
            throw new ResourceNotFoundException("User profile has already been deleted");
        }

        if (user.getRole() == UserRole.ROLE_ADMIN
                && userRepository.countActiveByRole(UserRole.ROLE_ADMIN) <= 1) {
            throw new AppException("The last admin account cannot be deleted", HttpStatus.CONFLICT);
        }

        user.setIsDeleted(true);
        user.setUpdatedAt(LocalDateTime.now());

        User savedUser = userRepository.save(user);
        return mapToResponse(savedUser);
    }

    @Override
    public UserRole assignRole(String userId, String requestedRole) {
        UserRole role;
        try {
            role = UserRole.valueOf(requestedRole.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new AppException("Role must be ROLE_CUSTOMER, ROLE_SELLER, or ROLE_ADMIN", HttpStatus.BAD_REQUEST);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (Boolean.TRUE.equals(user.getIsDeleted())) {
            throw new AppException("A deleted account cannot be assigned a role", HttpStatus.CONFLICT);
        }
        if (user.getRole() == UserRole.ROLE_ADMIN && role != UserRole.ROLE_ADMIN
                && userRepository.countActiveByRole(UserRole.ROLE_ADMIN) <= 1) {
            throw new AppException("The last admin account cannot be demoted", HttpStatus.CONFLICT);
        }
        user.setRole(role);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return role;
    }

    private UserProfileResponse mapToResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getPhoneNumber(),
                user.getFirstName(),
                user.getLastName(),
                user.getAddress(),
                user.getRole() != null ? user.getRole().name() : "ROLE_CUSTOMER",
                user.getIsDeleted(),
                user.getIsProfileComplete(),
                user.getIsVerified(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

    private java.util.Optional<User> findByIdentifier(String identifier) {
        return identifier != null && identifier.contains("@")
                ? userRepository.findByEmailIgnoreCase(identifier)
                : userRepository.findByPhoneNumber(identifier);
    }

}
