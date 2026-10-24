package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.UpdateUserRoleRequest;
import com.ebs.biocrop.dto.request.UserBlockedStatusRequest;
import com.ebs.biocrop.dto.request.UserDeletedStatusRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.AdminUserResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.UserRoleAssignmentResponse;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.AdminUserService;
import com.ebs.biocrop.service.RateLimitGuard;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.time.Duration;

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

    // List admin users with optional role and status filters.
    @GetMapping
    @Operation(summary = "List admin users with optional role and status filters")
    public ResponseEntity<ApiResponse<PagedResponse<AdminUserResponse>>> getUsers(
            @RequestParam(required = false) String role, @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean blocked, @RequestParam(required = false) Boolean deleted,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Page<AdminUserResponse> result = adminUsers.list(role, q, blocked, deleted, page, size);
        return ResponseEntity.ok(ApiResponse.ok("Admin users retrieved", PagedResponse.from(result, user -> user)));
    }

    // Get an admin-managed user by ID.
    @GetMapping("/{userId}")
    @Operation(summary = "Get an admin-managed user by ID")
    public ResponseEntity<ApiResponse<AdminUserResponse>> getUserById(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.ok("User retrieved", adminUsers.getById(userId)));
    }

    // Block or unblock a user.
    @PatchMapping("/{userId}/block")
    @Operation(summary = "Block or unblock a user", description = "Send {\"blocked\": true} to block or false to unblock.")
    public ResponseEntity<ApiResponse<AdminUserResponse>> updateUserBlockedStatus(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String userId,
            @Valid @RequestBody UserBlockedStatusRequest request) {
        rateLimitGuard.enforce("admin-user-write", admin.getId(), 60, Duration.ofMinutes(1));
        boolean blocked = request.blocked();
        return ResponseEntity.ok(ApiResponse.ok(blocked ? "User blocked" : "User unblocked",
                adminUsers.setBlockedStatus(userId, blocked)));
    }

    // Soft-delete or restore a user.
    @PatchMapping("/{userId}/deletion")
    @Operation(summary = "Soft-delete or restore a user", description = "Send {\"deleted\": true} to soft-delete or false to restore.")
    public ResponseEntity<ApiResponse<AdminUserResponse>> updateUserDeletedStatus(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String userId,
            @Valid @RequestBody UserDeletedStatusRequest request) {
        rateLimitGuard.enforce("admin-user-write", admin.getId(), 60, Duration.ofMinutes(1));
        boolean deleted = request.deleted();
        return ResponseEntity.ok(ApiResponse.ok(deleted ? "User deactivated" : "User restored",
                adminUsers.setDeletedStatus(userId, deleted)));
    }

    // Assign a role to an admin-managed user.
    @PatchMapping("/{userId}/role")
    @Operation(summary = "Assign a role to an admin-managed user", description = "Roles are ROLE_CUSTOMER, ROLE_SELLER, and ROLE_ADMIN. The last active admin cannot be demoted.")
    public ResponseEntity<ApiResponse<UserRoleAssignmentResponse>> updateUserRole(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String userId,
            @Valid @RequestBody UpdateUserRoleRequest request) {
        rateLimitGuard.enforce("admin-user-write", admin.getId(), 60, Duration.ofMinutes(1));
        UserRole role = adminUsers.assignRole(userId, request.getRole());
        return ResponseEntity.ok(ApiResponse.ok("User role updated", new UserRoleAssignmentResponse(userId, role)));
    }

    // Permanently delete a previously deactivated user
    @DeleteMapping({"/{userId}/permanent", "/{userId}"})
    @Operation(summary = "Permanently delete a user", description = "Permanently deletes a previously deactivated user account. Accounts with associated orders or product listings cannot be permanently deleted.")
    public ResponseEntity<ApiResponse<String>> permanentlyDeleteUser(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String userId) {
        rateLimitGuard.enforce("admin-user-write", admin.getId(), 10, Duration.ofMinutes(1));
        adminUsers.permanentlyDelete(admin.getId(), userId);
        return ResponseEntity.ok(ApiResponse.ok("User permanently deleted", userId));
    }
}
