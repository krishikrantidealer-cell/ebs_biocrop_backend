package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.UserProfileUpdateRequest;
import com.ebs.biocrop.dto.response.UserProfileResponse;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.UserService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserProfileResponse getProfileByPhoneNumber(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("User", "phoneNumber", phoneNumber));

        if (Boolean.TRUE.equals(user.getIsDelete())) {
            throw new ResourceNotFoundException("User profile not found or has been deleted");
        }

        return mapToResponse(user);
    }

    @Override
    public UserProfileResponse updateProfile(String phoneNumber, UserProfileUpdateRequest request) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("User", "phoneNumber", phoneNumber));

        if (Boolean.TRUE.equals(user.getIsDelete())) {
            throw new ResourceNotFoundException("User profile not found or has been deleted");
        }

        user.setFullName(request.getFullName());
        user.setAddress(request.getAddress());
        user.setUpdatedAt(LocalDateTime.now());

        User savedUser = userRepository.save(user);
        return mapToResponse(savedUser);
    }

    @Override
    public UserProfileResponse softDeleteUserProfile(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("User", "phoneNumber", phoneNumber));

        if (Boolean.TRUE.equals(user.getIsDelete())) {
            throw new ResourceNotFoundException("User profile has already been deleted");
        }

        user.setIsDelete(true);
        user.setUpdatedAt(LocalDateTime.now());

        User savedUser = userRepository.save(user);
        return mapToResponse(savedUser);
    }

    private UserProfileResponse mapToResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getPhoneNumber(),
                user.getFullName(),
                user.getAddress(),
                user.getRole() != null ? user.getRole().name() : "ROLE_CUSTOMER",
                user.getIsDelete(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
