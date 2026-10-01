package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.request.UserProfileUpdateRequest;
import com.ebs.biocrop.dto.response.UserProfileResponse;
import com.ebs.biocrop.entity.enums.UserRole;

public interface UserService {

    UserProfileResponse getProfileByPhoneNumber(String phoneNumber);

    UserProfileResponse updateProfile(String phoneNumber, UserProfileUpdateRequest request);

    UserProfileResponse softDeleteUserProfile(String phoneNumber);

    UserRole assignRole(String userId, String requestedRole);
}
