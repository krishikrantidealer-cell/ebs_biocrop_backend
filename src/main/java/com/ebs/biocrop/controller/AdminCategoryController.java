package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.CategoryWriteRequest;
import com.ebs.biocrop.dto.request.DeleteCategoriesRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.CategoryResponse;
import com.ebs.biocrop.dto.response.CountedListResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.service.CategoryService;
import com.ebs.biocrop.service.GcsImageStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import com.ebs.biocrop.service.RateLimitGuard;
import com.ebs.biocrop.security.user.CustomUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/admin/categories")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin categories")
@SecurityRequirement(name = "bearerAuth")
public class AdminCategoryController {
    private final CategoryService categoryService;
    private final GcsImageStorageService imageStorage;
    private final RateLimitGuard rateLimitGuard;

    public AdminCategoryController(CategoryService categoryService, GcsImageStorageService imageStorage,
                                   RateLimitGuard rateLimitGuard) {
        this.categoryService = categoryService;
        this.imageStorage = imageStorage;
        this.rateLimitGuard = rateLimitGuard;
    }

    // List all categories for admin with optional filters
    @GetMapping
    @Operation(summary = "List categories for administration", description = "Returns a bounded page; optionally filter by parentId or level.")
    public ResponseEntity<ApiResponse<PagedResponse<CategoryResponse>>> getCategories(
            @RequestParam(required = false) String parentId,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Category> result = categoryService.listAdmin(parentId, level, active, page, size);
        return ResponseEntity.ok(ApiResponse.ok("Admin categories retrieved",
                PagedResponse.from(result, category -> CategoryResponse.from(category, imageStorage))));
    }

    // Get a category by ID, including inactive categories
    @GetMapping("/{categoryId}")
    @Operation(summary = "Get a category for administration, including inactive categories")
    public ResponseEntity<ApiResponse<CategoryResponse>> getCategoryById(@PathVariable String categoryId) {
        return ResponseEntity.ok(ApiResponse.ok("Admin category retrieved",
                CategoryResponse.from(categoryService.getAdminById(categoryId), imageStorage)));
    }

    // Create a new category
    @PostMapping
    @Operation(summary = "Create a category")
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(
            @AuthenticationPrincipal CustomUserDetails admin,
            @Valid @RequestBody CategoryWriteRequest request) {
        rateLimitGuard.enforce("admin-category-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Category created", CategoryResponse.from(categoryService.create(request), imageStorage)));
    }

    // Update an existing category
    @PutMapping("/{categoryId}")
    @Operation(summary = "Update a category")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCategory(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String categoryId,
            @Valid @RequestBody CategoryWriteRequest request) {
        rateLimitGuard.enforce("admin-category-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Category updated",
                CategoryResponse.from(categoryService.update(categoryId, request), imageStorage)));
    }

    // Deactivate a category (soft delete)
    @DeleteMapping("/{categoryId}")
    @Operation(summary = "Deactivate a category")
    public ResponseEntity<ApiResponse<CategoryResponse>> deactivateCategory(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String categoryId) {
        rateLimitGuard.enforce("admin-category-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Category deactivated",
                CategoryResponse.from(categoryService.deactivate(categoryId), imageStorage)));
    }

    // Permanently delete an unused category
    @DeleteMapping("/{categoryId}/permanent")
    @Operation(summary = "Permanently delete an unused category")
    public ResponseEntity<ApiResponse<CountedListResponse<String>>> permanentlyDeleteCategory(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String categoryId) {
        rateLimitGuard.enforce("admin-category-write", admin.getId(), 10, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Category permanently deleted",
                CountedListResponse.from(categoryService.permanentlyDelete(categoryId))));
    }

    // Permanently delete multiple unused categories
    @DeleteMapping("/permanent")
    @Operation(summary = "Permanently delete multiple unused categories", description = "All categories are validated before deletion. Categories with child categories or assigned products are rejected.")
    public ResponseEntity<ApiResponse<CountedListResponse<String>>> permanentlyDeleteCategories(
            @AuthenticationPrincipal CustomUserDetails admin,
            @Valid @RequestBody DeleteCategoriesRequest request) {
        rateLimitGuard.enforce("admin-category-write", admin.getId(), 10, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Categories permanently deleted",
                CountedListResponse.from(categoryService.permanentlyDeleteMany(request.categoryIds()))));
    }


    // Upload or replace a category icon image
    @PostMapping(value = "/{categoryId}/icon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload or replace a category icon image")
    public ResponseEntity<ApiResponse<CategoryResponse>> uploadCategoryIcon(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String categoryId,
            @RequestPart("file") MultipartFile file) {
        rateLimitGuard.enforce("admin-category-icon-upload", admin.getId(), 10, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Category icon uploaded",
                CategoryResponse.from(categoryService.uploadIcon(categoryId, file), imageStorage)));
    }

    // Delete a category icon image
    @DeleteMapping("/{categoryId}/icon")
    @Operation(summary = "Delete a category icon image")
    public ResponseEntity<ApiResponse<CategoryResponse>> deleteCategoryIcon(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String categoryId) {
        rateLimitGuard.enforce("admin-category-icon-delete", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Category icon deleted",
                CategoryResponse.from(categoryService.deleteIcon(categoryId), imageStorage)));
    }
}
