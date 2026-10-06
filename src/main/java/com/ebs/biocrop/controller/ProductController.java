package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.PublicProductResponse;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.service.ProductCatalogService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Public product catalog endpoints. List endpoints use bounded server-side pagination.")
public class ProductController {
    private final ProductCatalogService productCatalog;
    private final GcsImageStorageService imageStorage;
    private final RedisJsonCache redisCache;
    private final TypeFactory typeFactory;

    public ProductController(ProductCatalogService productCatalog, GcsImageStorageService imageStorage,
                             RedisJsonCache redisCache, ObjectMapper objectMapper) {
        this.productCatalog = productCatalog;
        this.imageStorage = imageStorage;
        this.redisCache = redisCache;
        this.typeFactory = objectMapper.getTypeFactory();
    }

    @GetMapping
    @Operation(summary = "List available products")
    public ResponseEntity<ApiResponse<PagedResponse<PublicProductResponse>>> getProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return pageResponse("list|page=" + page + "|size=" + size,
                () -> productCatalog.listPublic(page, size), "Products retrieved successfully");
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get available product details")
    public ResponseEntity<ApiResponse<PublicProductResponse>> getProductById(@PathVariable String id) {
        PublicProductResponse response = redisCache.getOrLoad("public-products", "id=" + id,
                typeFactory.constructType(PublicProductResponse.class), Duration.ofSeconds(30),
                () -> PublicProductResponse.from(productCatalog.getPublic(id), imageStorage));
        return ResponseEntity.ok(ApiResponse.ok("Product retrieved successfully", response));
    }

    @GetMapping("/category/{categoryId}")
    @Operation(summary = "List available products in a category subtree")
    public ResponseEntity<ApiResponse<PagedResponse<PublicProductResponse>>> getProductsByCategory(
            @PathVariable String categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return pageResponse("category=" + categoryId + "|page=" + page + "|size=" + size,
                () -> productCatalog.listByCategory(categoryId, page, size), "Products retrieved successfully");
    }

    @GetMapping("/search")
    @Operation(summary = "Search available products")
    public ResponseEntity<ApiResponse<PagedResponse<PublicProductResponse>>> searchProducts(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return pageResponse("search=" + q + "|page=" + page + "|size=" + size,
                () -> productCatalog.search(q, page, size), "Products retrieved successfully");
    }

    private ResponseEntity<ApiResponse<PagedResponse<PublicProductResponse>>> pageResponse(
        String queryKey, java.util.function.Supplier<Page<Product>> loader, String message) {
        JavaType responseType = typeFactory.constructParametricType(PagedResponse.class, PublicProductResponse.class);
        PagedResponse<PublicProductResponse> response = redisCache.getOrLoad(
                "public-products", queryKey, responseType, Duration.ofSeconds(30),
                () -> PagedResponse.from(loader.get(), product -> PublicProductResponse.from(product, imageStorage)));
        return ResponseEntity.ok(ApiResponse.ok(message, response));
    }
}
