package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.CategoryResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.service.CategoryService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Public category browse APIs. Category hierarchy is limited to levels 0 through 2.")
public class CategoryController {
    private final CategoryService categoryService;
    private final RedisJsonCache redisCache;
    private final TypeFactory typeFactory;

    public CategoryController(CategoryService categoryService, RedisJsonCache redisCache,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.categoryService = categoryService;
        this.redisCache = redisCache;
        this.typeFactory = objectMapper.getTypeFactory();
    }

    @GetMapping
    @Operation(summary = "List active categories", description = "Returns a bounded page. Use parentId to list direct children or level to filter category depth.")
    public ResponseEntity<ApiResponse<PagedResponse<CategoryResponse>>> getCategories(
            @RequestParam(required = false) String parentId,
            @RequestParam(required = false) Integer level,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String key = "parent=" + parentId + "|level=" + level + "|page=" + page + "|size=" + size;
        JavaType responseType = typeFactory.constructParametricType(PagedResponse.class, CategoryResponse.class);
        PagedResponse<CategoryResponse> response = redisCache.getOrLoad(
                "public-categories", key, responseType, Duration.ofMinutes(10),
                () -> PagedResponse.from(categoryService.listPublic(parentId, level, page, size), CategoryResponse::from));
        return ResponseEntity.ok(ApiResponse.ok("Categories retrieved successfully", response));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get active category by ID")
    public ResponseEntity<ApiResponse<CategoryResponse>> getCategoryById(@PathVariable String id) {
        CategoryResponse response = redisCache.getOrLoad(
                "public-categories", "id=" + id, typeFactory.constructType(CategoryResponse.class),
                Duration.ofMinutes(10), () -> CategoryResponse.from(categoryService.getPublic(id)));
        return ResponseEntity.ok(ApiResponse.ok("Category retrieved successfully", response));
    }
}
