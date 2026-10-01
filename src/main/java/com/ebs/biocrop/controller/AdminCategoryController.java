package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.CategoryWriteRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.CategoryResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/categories")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin categories")
@SecurityRequirement(name = "bearerAuth")
public class AdminCategoryController {
    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @Operation(summary = "List categories for administration", description = "Returns a bounded page; optionally filter by parentId or level.")
    public ResponseEntity<ApiResponse<PagedResponse<CategoryResponse>>> list(
            @RequestParam(required = false) String parentId,
            @RequestParam(required = false) Integer level,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Category> result = categoryService.listAdmin(parentId, level, page, size);
        return ResponseEntity.ok(ApiResponse.ok("Admin categories retrieved",
                PagedResponse.from(result, CategoryResponse::from)));
    }

    @PostMapping
    @Operation(summary = "Create a category")
    public ResponseEntity<ApiResponse<CategoryResponse>> create(@Valid @RequestBody CategoryWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Category created", CategoryResponse.from(categoryService.create(request))));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a category")
    public ResponseEntity<ApiResponse<CategoryResponse>> update(@PathVariable String id,
                                                                 @Valid @RequestBody CategoryWriteRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Category updated",
                CategoryResponse.from(categoryService.update(id, request))));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate a category")
    public ResponseEntity<ApiResponse<CategoryResponse>> deactivate(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("Category deactivated",
                CategoryResponse.from(categoryService.deactivate(id))));
    }
}
