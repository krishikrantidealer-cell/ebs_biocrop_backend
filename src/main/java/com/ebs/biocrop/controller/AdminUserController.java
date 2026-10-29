package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.UpdateUserRoleRequest;
import com.ebs.biocrop.dto.request.AdminManagedUserCreateRequest;
import com.ebs.biocrop.dto.request.AdminManagedUserUpdateRequest;
import com.ebs.biocrop.dto.request.SellerKycReviewRequest;
import com.ebs.biocrop.dto.request.UserBlockedStatusRequest;
import com.ebs.biocrop.dto.request.UserDeletedStatusRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.AdminUserResponse;
import com.ebs.biocrop.dto.response.AdminSellerDashboardResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.UserRoleAssignmentResponse;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.AdminUserService;
import com.ebs.biocrop.service.RateLimitGuard;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
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

    @PostMapping("/customers")
    @Operation(summary = "Admin creates a customer account")
    public ResponseEntity<ApiResponse<AdminUserResponse>> createCustomer(
            @AuthenticationPrincipal CustomUserDetails admin,
            @Valid @RequestBody AdminManagedUserCreateRequest request) {
        return createManagedUser(admin, "CUSTOMER", request);
    }

    @PostMapping("/sellers")
    @Operation(summary = "Admin creates a seller account")
    public ResponseEntity<ApiResponse<AdminUserResponse>> createSeller(
            @AuthenticationPrincipal CustomUserDetails admin,
            @Valid @RequestBody AdminManagedUserCreateRequest request) {
        return createManagedUser(admin, "SELLER", request);
    }

    private ResponseEntity<ApiResponse<AdminUserResponse>> createManagedUser(
            CustomUserDetails admin, String role, AdminManagedUserCreateRequest request) {
        rateLimitGuard.enforce("admin-user-write", admin.getId(), 30, Duration.ofMinutes(1));
        AdminUserResponse created = adminUsers.createManagedUser(role, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("User created", created));
    }

    @PatchMapping("/{userId}")
    @Operation(summary = "Update an admin-managed customer or seller profile")
    public ResponseEntity<ApiResponse<AdminUserResponse>> updateManagedUser(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String userId,
            @Valid @RequestBody AdminManagedUserUpdateRequest request) {
        rateLimitGuard.enforce("admin-user-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("User updated", adminUsers.updateManagedUser(userId, request)));
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

    @GetMapping("/{userId}/dashboard")
    @Operation(summary = "Get a seller profile and operational dashboard counts")
    public ResponseEntity<ApiResponse<AdminSellerDashboardResponse>> getSellerDashboard(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.ok("Seller dashboard retrieved", adminUsers.getSellerDashboard(userId)));
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

    @PatchMapping("/{userId}/kyc")
    @Operation(summary = "Approve or return a seller KYC submission")
    public ResponseEntity<ApiResponse<AdminUserResponse>> reviewSellerKyc(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String userId,
            @Valid @RequestBody SellerKycReviewRequest request) {
        rateLimitGuard.enforce("admin-user-write", admin.getId(), 60, Duration.ofMinutes(1));
        boolean approved = request.approved();
        return ResponseEntity.ok(ApiResponse.ok(approved ? "Seller KYC approved" : "Seller KYC returned for review",
                adminUsers.reviewSellerKyc(userId, approved)));
    }

    // Assign a role to an admin-managed user.
    @PatchMapping("/{userId}/role")
    @Operation(summary = "Validate an admin-managed user's role", description = "Roles are immutable after account creation. Sending the current role is idempotent; changing it returns 409 to protect role-specific data and relationships.")
    public ResponseEntity<ApiResponse<UserRoleAssignmentResponse>> updateUserRole(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String userId,
            @Valid @RequestBody UpdateUserRoleRequest request) {
        rateLimitGuard.enforce("admin-user-write", admin.getId(), 60, Duration.ofMinutes(1));
        UserRole role = adminUsers.assignRole(userId, request.getRole());
        return ResponseEntity.ok(ApiResponse.ok("User role confirmed", new UserRoleAssignmentResponse(userId, role)));
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
