package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.PublicProductResponse;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.service.ProductCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Public product catalog endpoints. List endpoints use bounded server-side pagination.")
public class ProductController {
    private final ProductCatalogService productCatalog;
    private final GcsImageStorageService imageStorage;

    public ProductController(ProductCatalogService productCatalog, GcsImageStorageService imageStorage) {
        this.productCatalog = productCatalog;
        this.imageStorage = imageStorage;
    }

    @GetMapping
    @Operation(summary = "List available products")
    public ResponseEntity<ApiResponse<PagedResponse<PublicProductResponse>>> getProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return pageResponse(productCatalog.listPublic(page, size), "Products retrieved successfully");
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get available product details")
    public ResponseEntity<ApiResponse<PublicProductResponse>> getProductById(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("Product retrieved successfully",
                PublicProductResponse.from(productCatalog.getPublic(id), imageStorage)));
    }

    @GetMapping("/category/{categoryId}")
    @Operation(summary = "List available products in a category subtree")
    public ResponseEntity<ApiResponse<PagedResponse<PublicProductResponse>>> getProductsByCategory(
            @PathVariable String categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return pageResponse(productCatalog.listByCategory(categoryId, page, size), "Products retrieved successfully");
    }

    @GetMapping("/search")
    @Operation(summary = "Search available products")
    public ResponseEntity<ApiResponse<PagedResponse<PublicProductResponse>>> searchProducts(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return pageResponse(productCatalog.search(q, page, size), "Products retrieved successfully");
    }

    private ResponseEntity<ApiResponse<PagedResponse<PublicProductResponse>>> pageResponse(
        Page<Product> products, String message) {
        return ResponseEntity.ok(ApiResponse.ok(message,
                PagedResponse.from(
                        products,
                        product -> PublicProductResponse.from(product, imageStorage))));
    }
}
