package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.CollectionWriteRequest;
import com.ebs.biocrop.dto.request.CollectionPatchRequest;
import com.ebs.biocrop.dto.request.DeleteCollectionsRequest;
import com.ebs.biocrop.dto.request.DeleteSubCollectionsRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.CollectionResponse;
import com.ebs.biocrop.dto.response.CountedListResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.ProductCollectionService;
import com.ebs.biocrop.service.RateLimitGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    // List all collections
    @GetMapping
    @Operation(summary = "List all merchandising collections")
    public ResponseEntity<ApiResponse<PagedResponse<CollectionResponse>>> listCollections(
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<CollectionResponse> result = collections.listAdmin(active, page, size);
        return ResponseEntity.ok(ApiResponse.ok("Collections retrieved", PagedResponse.from(result, value -> value)));
    }

    // Get a collection by ID
    @GetMapping("/{collectionId}")
    @Operation(summary = "Get a collection by ID")
    public ResponseEntity<ApiResponse<CollectionResponse>> getCollectionById(@PathVariable String collectionId) {
        return ResponseEntity.ok(ApiResponse.ok("Collection retrieved", collections.getAdminById(collectionId)));
    }

    // Create a new collection
    @PostMapping
    @Operation(summary = "Create a merchandising collection")
    public ResponseEntity<ApiResponse<CollectionResponse>> createCollection(
            @AuthenticationPrincipal CustomUserDetails admin,
            @Valid @RequestBody CollectionWriteRequest request) {
        rateLimitGuard.enforce("admin-collection-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Collection created", collections.create(request)));
    }

    // Replace an existing collection
    @PutMapping("/{collectionId}")
    @Operation(summary = "Replace a collection", description = "Omitted optional fields reset to defaults; omitted subCollections clears the subcollection list.")
    public ResponseEntity<ApiResponse<CollectionResponse>> updateCollection(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String collectionId,
            @Valid @RequestBody CollectionWriteRequest request) {
        rateLimitGuard.enforce("admin-collection-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Collection replaced", collections.replace(collectionId, request)));
    }

    // Partially update an existing collection
    @PatchMapping("/{collectionId}")
    @Operation(summary = "Partially update a collection", description = "Only supplied fields change; an empty subCollections list removes all subcollections.")
    public ResponseEntity<ApiResponse<CollectionResponse>> patchCollection(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String collectionId,
            @Valid @RequestBody CollectionPatchRequest request) {
        rateLimitGuard.enforce("admin-collection-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Collection updated", collections.update(collectionId, request)));
    }

    // Permanently delete a single collection or multiple collections (unified single & bulk endpoint)
    @DeleteMapping({"", "/permanent", "/{collectionId}", "/{collectionId}/permanent"})
    @Operation(summary = "Permanently delete one or more collections",
               description = "Delete a single collection via path or body, or delete multiple collections via body with collectionIds.")
    public ResponseEntity<ApiResponse<CountedListResponse<String>>> permanentlyDeleteCollections(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable(required = false) String collectionId,
            @Valid @RequestBody(required = false) DeleteCollectionsRequest request) {
        rateLimitGuard.enforce("admin-collection-write", admin.getId(), 30, Duration.ofMinutes(1));
        List<String> targetIds = resolveCollectionIds(collectionId, request != null ? request.collectionIds() : null);
        if (targetIds.isEmpty()) {
            throw new AppException("At least one collection ID is required for permanent deletion", HttpStatus.BAD_REQUEST);
        }
        List<String> deleted = collections.permanentlyDeleteMany(targetIds);
        String message = deleted.size() == 1 ? "Collection permanently deleted" : "Collections permanently deleted";
        return ResponseEntity.ok(ApiResponse.ok(message, CountedListResponse.from(deleted)));
    }

    // Permanently delete a subcollection
    @DeleteMapping("/{collectionId}/subcollections/{subCollectionId}")
    @Operation(summary = "Delete one subcollection and remove its product assignments")
    public ResponseEntity<ApiResponse<CountedListResponse<String>>> deleteSubCollection(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String collectionId,
            @PathVariable String subCollectionId) {
        rateLimitGuard.enforce("admin-collection-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Subcollection deleted",
                CountedListResponse.from(collections.deleteSubCollections(collectionId, java.util.List.of(subCollectionId)))));
    }

    // Permanently delete multiple subcollections
    @DeleteMapping("/{collectionId}/subcollections")
    @Operation(summary = "Delete one or more subcollections and remove their product assignments")
    public ResponseEntity<ApiResponse<CountedListResponse<String>>> deleteSubCollections(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String collectionId,
            @Valid @RequestBody DeleteSubCollectionsRequest request) {
        rateLimitGuard.enforce("admin-collection-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Subcollections deleted",
                CountedListResponse.from(collections.deleteSubCollections(collectionId, request.subCollectionIds()))));
    }

    private List<String> resolveCollectionIds(String pathCollectionId, List<String> bodyCollectionIds) {
        Set<String> ids = new LinkedHashSet<>();
        if (pathCollectionId != null && !pathCollectionId.isBlank()) {
            ids.add(pathCollectionId.trim());
        }
        if (bodyCollectionIds != null) {
            for (String id : bodyCollectionIds) {
                if (id != null && !id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        }
        return new ArrayList<>(ids);
    }
}
