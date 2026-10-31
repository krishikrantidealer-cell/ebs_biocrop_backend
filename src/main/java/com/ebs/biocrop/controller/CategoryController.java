package com.ebs.biocrop.controller;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import java.util.List;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.CategoryResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.service.CategoryService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.service.GcsImageStorageService;
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
    private final GcsImageStorageService imageStorage;

    public CategoryController(CategoryService categoryService, RedisJsonCache redisCache,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                              GcsImageStorageService imageStorage) {
        this.categoryService = categoryService;
        this.redisCache = redisCache;
        this.typeFactory = objectMapper.getTypeFactory();
        this.imageStorage = imageStorage;
    }

    // List active categories.
    @GetMapping
    @Operation(summary = "List active categories", description = "Returns a bounded page. Use parentId to list direct children or level to filter category depth.")
    public ResponseEntity<ApiResponse<PagedResponse<CategoryResponse>>> getCategories(
            @RequestParam(required = false) String parentId,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int resolvedPage = page != null ? Math.max(0, page) : 0;
        int resolvedSize = size != null ? size : (page != null ? 20 : PageRequestSupport.MAX_SIZE);
        String key = "parent=" + parentId + "|level=" + level + "|page=" + resolvedPage + "|size=" + resolvedSize;
        JavaType responseType = typeFactory.constructParametricType(PagedResponse.class, CategoryResponse.class);
        PagedResponse<CategoryResponse> response = redisCache.getOrLoad(
                "public-categories", key, responseType, Duration.ofMinutes(10),
                () -> {
                    Page<Category> result = categoryService.listPublic(parentId, level, resolvedPage, resolvedSize);
                    List<CategoryResponse> content = result.getContent().stream()
                            .map(category -> CategoryResponse.from(category, imageStorage))
                            .toList();
                    int reportedSize = (result.getSize() >= PageRequestSupport.MAX_SIZE && result.getTotalPages() <= 1)
                            ? content.size()
                            : result.getSize();
                    return new PagedResponse<>(content, result.getNumber(), reportedSize,
                            result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast());
                });
        return ResponseEntity.ok(ApiResponse.ok("Categories retrieved successfully", response));
    }

    // Get active category by ID.
    @GetMapping("/{id}")
    @Operation(summary = "Get active category by ID")
    public ResponseEntity<ApiResponse<CategoryResponse>> getCategoryById(@PathVariable String id) {
        CategoryResponse response = redisCache.getOrLoad(
                "public-categories", "id=" + id, typeFactory.constructType(CategoryResponse.class),
                Duration.ofMinutes(10), () -> CategoryResponse.from(categoryService.getPublic(id), imageStorage));
        return ResponseEntity.ok(ApiResponse.ok("Category retrieved successfully", response));
    }
}
