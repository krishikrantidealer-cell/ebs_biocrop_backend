package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.CollectionWriteRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.CollectionResponse;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.ProductCollectionService;
import com.ebs.biocrop.service.RateLimitGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/admin/collections")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin collections", description = "Admin-only merchandising collection management.")
@SecurityRequirement(name = "bearerAuth")
public class AdminProductCollectionController {
    private final ProductCollectionService collections;
    private final RateLimitGuard rateLimitGuard;

    public AdminProductCollectionController(ProductCollectionService collections, RateLimitGuard rateLimitGuard) {
        this.collections = collections;
        this.rateLimitGuard = rateLimitGuard;
    }

    @PostMapping
    @Operation(summary = "Create a collection")
    public ResponseEntity<ApiResponse<CollectionResponse>> create(
            @AuthenticationPrincipal CustomUserDetails admin,
            @Valid @RequestBody CollectionWriteRequest request) {
        rateLimitGuard.enforce("admin-collection-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Collection created", collections.create(request)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a collection")
    public ResponseEntity<ApiResponse<CollectionResponse>> update(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String id,
            @Valid @RequestBody CollectionWriteRequest request) {
        rateLimitGuard.enforce("admin-collection-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Collection updated", collections.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate a collection")
    public ResponseEntity<ApiResponse<CollectionResponse>> delete(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String id) {
        rateLimitGuard.enforce("admin-collection-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Collection deactivated", collections.deactivate(id)));
    }
}
