package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.UpdateUserRoleRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.service.AdminUserService;
import com.ebs.biocrop.dto.response.AdminUserResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.RateLimitGuard;
import java.time.Duration;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin users")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {
    private final AdminUserService adminUsers;
    private final RateLimitGuard rateLimitGuard;

    public AdminUserController(AdminUserService adminUsers, RateLimitGuard rateLimitGuard) {
        this.adminUsers = adminUsers;
        this.rateLimitGuard = rateLimitGuard;
    }

    @GetMapping
    @Operation(summary = "List admin users with optional role and status filters")
    public ResponseEntity<ApiResponse<PagedResponse<AdminUserResponse>>> listAdminUsers(
            @RequestParam(required = false) String role, @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean blocked, @RequestParam(required = false) Boolean deleted,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Page<AdminUserResponse> result=adminUsers.listAdminUsers(role,q,blocked,deleted,page,size);
        return ResponseEntity.ok(ApiResponse.ok("Admin users retrieved",PagedResponse.from(result, x->x)));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<AdminUserResponse>> getAdminUserById(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.ok("User retrieved",adminUsers.getAdminUserById(userId)));
    }

    @PatchMapping("/{userId}/block")
    public ResponseEntity<ApiResponse<AdminUserResponse>> setAdminUserBlockedStatus(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String userId, @RequestParam boolean blocked) {
        rateLimitGuard.enforce("admin-user-write",admin.getId(),60,Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok(blocked?"User blocked":"User unblocked",adminUsers.setAdminUserBlockedStatus(userId,blocked)));
    }

    @PatchMapping("/{userId}/deletion")
    public ResponseEntity<ApiResponse<AdminUserResponse>> setAdminUserDeletedStatus(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String userId, @RequestParam boolean deleted) {
        rateLimitGuard.enforce("admin-user-write",admin.getId(),60,Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok(deleted?"User deactivated":"User restored",adminUsers.setAdminUserDeletedStatus(userId,deleted)));
    }

    @PatchMapping("/{userId}/role")
    @Operation(summary = "Assign a role to an admin-managed user", description = "Roles are ROLE_CUSTOMER, ROLE_SELLER, and ROLE_ADMIN. The last active admin cannot be demoted.")
    public ResponseEntity<ApiResponse<RoleAssignment>> assignAdminUserRole(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String userId,
                                                                   @Valid @RequestBody UpdateUserRoleRequest request) {
        rateLimitGuard.enforce("admin-user-write",admin.getId(),60,Duration.ofMinutes(1));
        UserRole role = adminUsers.assignAdminUserRole(userId, request.getRole());
        return ResponseEntity.ok(ApiResponse.ok("User role updated", new RoleAssignment(userId, role)));
    }

    public record RoleAssignment(String userId, UserRole role) { }
}
