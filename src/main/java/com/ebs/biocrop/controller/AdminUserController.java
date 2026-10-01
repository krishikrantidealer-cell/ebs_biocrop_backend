package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.UpdateUserRoleRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final UserService users;

    public AdminUserController(UserService users) {
        this.users = users;
    }

    @PatchMapping("/{userId}/role")
    @Operation(summary = "Assign an account role", description = "Roles are ROLE_CUSTOMER, ROLE_SELLER, and ROLE_ADMIN. The last active admin cannot be demoted.")
    public ResponseEntity<ApiResponse<RoleAssignment>> assignRole(@PathVariable String userId,
                                                                   @Valid @RequestBody UpdateUserRoleRequest request) {
        UserRole role = users.assignRole(userId, request.getRole());
        return ResponseEntity.ok(ApiResponse.ok("User role updated", new RoleAssignment(userId, role)));
    }

    public record RoleAssignment(String userId, UserRole role) { }
}
