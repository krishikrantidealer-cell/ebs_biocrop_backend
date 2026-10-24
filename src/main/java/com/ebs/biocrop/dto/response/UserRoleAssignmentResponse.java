package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.enums.UserRole;

public record UserRoleAssignmentResponse(String userId, UserRole role) {
}
