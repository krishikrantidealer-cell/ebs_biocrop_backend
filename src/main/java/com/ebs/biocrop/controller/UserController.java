package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.UserProfileUpdateRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.UserProfileResponse;
import com.ebs.biocrop.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getCurrentUserProfile(Authentication authentication) {
        String phoneNumber = authentication.getName();
        UserProfileResponse profile = userService.getProfileByPhoneNumber(phoneNumber);
        return ResponseEntity.ok(ApiResponse.ok("User profile retrieved successfully", profile));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateUserProfile(
            Authentication authentication,
            @Valid @RequestBody UserProfileUpdateRequest request) {
        String phoneNumber = authentication.getName();
        UserProfileResponse updatedProfile = userService.updateProfile(phoneNumber, request);
        return ResponseEntity.ok(ApiResponse.ok("User profile completed and updated successfully", updatedProfile));
    }

    @DeleteMapping("/profile")
    public ResponseEntity<ApiResponse<UserProfileResponse>> deleteUserProfile(Authentication authentication) {
        String phoneNumber = authentication.getName();
        UserProfileResponse deletedProfile = userService.softDeleteUserProfile(phoneNumber);
        return ResponseEntity.ok(ApiResponse.ok("User profile deleted successfully", deletedProfile));
    }
}
