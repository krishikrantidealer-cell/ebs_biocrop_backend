package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.response.AdminUserResponse;
import com.ebs.biocrop.entity.enums.UserRole;
import org.springframework.data.domain.Page;

public interface AdminUserService {
    Page<AdminUserResponse> list(String role, String query, Boolean blocked, Boolean deleted, int page, int size);
    AdminUserResponse getById(String id);
    AdminUserResponse setBlockedStatus(String id, boolean blocked);
    AdminUserResponse setDeletedStatus(String id, boolean deleted);
    UserRole assignRole(String id, String requestedRole);
}
