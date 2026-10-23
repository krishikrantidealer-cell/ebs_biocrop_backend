package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.response.AdminUserResponse;
import com.ebs.biocrop.entity.enums.UserRole;
import org.springframework.data.domain.Page;

public interface AdminUserService {
    Page<AdminUserResponse> listAdminUsers(String role, String query, Boolean blocked, Boolean deleted, int page, int size);
    AdminUserResponse getAdminUserById(String id);
    AdminUserResponse setAdminUserBlockedStatus(String id, boolean blocked);
    AdminUserResponse setAdminUserDeletedStatus(String id, boolean deleted);
    UserRole assignAdminUserRole(String id, String requestedRole);
}
