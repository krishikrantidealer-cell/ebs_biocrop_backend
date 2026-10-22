package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.CollectionResponse;
import com.ebs.biocrop.dto.response.CountedListResponse;
import com.ebs.biocrop.service.ProductCollectionService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/collections")
@Tag(name = "Collections", description = "Public merchandising collections and subcollections.")
public class ProductCollectionController {
    private final ProductCollectionService collections;
    private final RedisJsonCache redisCache;
    private final TypeFactory typeFactory;

    public ProductCollectionController(ProductCollectionService collections, RedisJsonCache redisCache,
                                       ObjectMapper objectMapper) {
        this.collections = collections;
        this.redisCache = redisCache;
        this.typeFactory = objectMapper.getTypeFactory();
    }

    @GetMapping
    @Operation(summary = "List active collections")
    public ResponseEntity<ApiResponse<CountedListResponse<CollectionResponse>>> getCollections() {
        JavaType type = typeFactory.constructParametricType(CountedListResponse.class, CollectionResponse.class);
        CountedListResponse<CollectionResponse> result = redisCache.getOrLoad("public-collections", "all",
                type, Duration.ofMinutes(5), () -> CountedListResponse.from(collections.listPublic()));
        return ResponseEntity.ok(ApiResponse.ok("Collections retrieved", result));
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get an active collection by slug")
    public ResponseEntity<ApiResponse<CollectionResponse>> getCollectionBySlug(@PathVariable String slug) {
        String key = "slug=" + slug.trim().toLowerCase(java.util.Locale.ROOT);
        CollectionResponse result = redisCache.getOrLoad("public-collections", key,
                typeFactory.constructType(CollectionResponse.class), Duration.ofMinutes(5),
                () -> collections.getPublicBySlug(slug));
        return ResponseEntity.ok(ApiResponse.ok("Collection retrieved", result));
    }
}
