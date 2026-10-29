package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.response.AdminUserResponse;
import com.ebs.biocrop.dto.response.AdminSellerDashboardResponse;
import com.ebs.biocrop.dto.request.AdminManagedUserCreateRequest;
import com.ebs.biocrop.dto.request.AdminManagedUserUpdateRequest;
import com.ebs.biocrop.entity.enums.UserRole;
import org.springframework.data.domain.Page;

public interface AdminUserService {
    AdminUserResponse createManagedUser(String role, AdminManagedUserCreateRequest request);
    AdminUserResponse updateManagedUser(String id, AdminManagedUserUpdateRequest request);
    AdminUserResponse replaceManagedUser(String id, AdminManagedUserUpdateRequest request);
    Page<AdminUserResponse> list(String role, String query, Boolean blocked, Boolean deleted, int page, int size);
    AdminUserResponse getById(String id);
    AdminSellerDashboardResponse getSellerDashboard(String id);
    AdminUserResponse setBlockedStatus(String id, boolean blocked);
    AdminUserResponse setDeletedStatus(String id, boolean deleted);
    AdminUserResponse reviewSellerKyc(String id, boolean approved);
    UserRole assignRole(String id, String requestedRole);
    void permanentlyDelete(String adminId, String id);
}
