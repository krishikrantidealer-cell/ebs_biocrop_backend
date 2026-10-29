package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.UserProfileUpdateRequest;
import com.ebs.biocrop.dto.response.UserProfileResponse;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.service.UserService;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;
    private final RedisJsonCache redisCache;

    public UserServiceImpl(UserRepository userRepository, MongoTemplate mongoTemplate, RedisJsonCache redisCache) {
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
        this.redisCache = redisCache;
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
    @Transactional
    public UserProfileResponse updateProfile(String phoneNumber, UserProfileUpdateRequest request) {
        User user = findByIdentifier(phoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("User", "phoneNumber", phoneNumber));

        if (Boolean.TRUE.equals(user.getIsDeleted()) || Boolean.TRUE.equals(user.getIsBlocked())) {
            throw new ResourceNotFoundException("User profile is unavailable");
        }
        if (user.getRole() != UserRole.ROLE_CUSTOMER && user.getRole() != UserRole.ROLE_SELLER) {
            throw new AppException("This profile cannot be updated through the customer/seller endpoint", HttpStatus.FORBIDDEN);
        }
        if (user.getRole() == UserRole.ROLE_CUSTOMER
                && request.getShopName() != null && !request.getShopName().isBlank()) {
            throw new AppException("Shop name can only be set on a seller profile", HttpStatus.BAD_REQUEST);
        }

        String oldShopName = user.getShopName();
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setAddress(request.getAddress());
        if (user.getRole() == UserRole.ROLE_SELLER && request.getShopName() != null) {
            user.setShopName(request.getShopName());
        }
        if (user.getRole() == UserRole.ROLE_SELLER
                && (user.getShopName() == null || user.getShopName().isBlank())) {
            throw new AppException("Shop name is required to complete a seller profile", HttpStatus.BAD_REQUEST);
        }
        boolean profileComplete = user.hasCompleteProfile();
        if (!profileComplete) {
            throw new AppException("Complete all required profile fields and provide a valid 6-digit pincode", HttpStatus.BAD_REQUEST);
        }
        user.setIsProfileComplete(profileComplete);
        // Profile updates require an authenticated token issued after OTP verification.
        user.setIsVerified(true);
        user.setUpdatedAt(LocalDateTime.now());

        User savedUser = userRepository.save(user);
        if (savedUser.getRole() == UserRole.ROLE_SELLER
                && !Objects.equals(oldShopName, savedUser.getShopName())) {
            mongoTemplate.updateMulti(
                    Query.query(Criteria.where("sellerId").is(savedUser.getId())),
                    new Update().set("sellerName", savedUser.getShopName())
                            .set("updatedAt", savedUser.getUpdatedAt())
                            .inc("version", 1),
                    "products");
            redisCache.invalidateRegion("public-products");
            redisCache.invalidateRegion("public-collections");
        }
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

    private UserProfileResponse mapToResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getPhoneNumber(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole() == UserRole.ROLE_SELLER ? user.getShopName() : null,
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
