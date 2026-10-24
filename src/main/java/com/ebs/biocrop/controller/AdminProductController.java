package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.ProductManagementResponse;
import com.ebs.biocrop.dto.response.CountedListResponse;
import com.ebs.biocrop.dto.request.DeleteProductImagesRequest;
import com.ebs.biocrop.dto.request.AdminProductWriteRequest;
import com.ebs.biocrop.dto.request.AdminProductPatchRequest;
import com.ebs.biocrop.dto.request.AdminProductVariantPatchRequest;
import com.ebs.biocrop.dto.request.AdminProductVariantWriteRequest;
import com.ebs.biocrop.dto.request.ProductCollectionAssignmentRequest;
import com.ebs.biocrop.dto.request.ProductReviewRequest;
import com.ebs.biocrop.dto.request.ProductAvailabilityRequest;
import com.ebs.biocrop.dto.request.ProductFeaturedRequest;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.service.AdminProductService;
import com.ebs.biocrop.service.RateLimitGuard;
import com.ebs.biocrop.security.user.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import jakarta.validation.Valid;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/admin/products")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin products")
@SecurityRequirement(name = "bearerAuth")
public class AdminProductController {
    private final AdminProductService products;
    private final RateLimitGuard rateLimitGuard;

    public AdminProductController(AdminProductService products, RateLimitGuard rateLimitGuard) {
        this.products = products;
        this.rateLimitGuard = rateLimitGuard;
    }

    // List admin-managed products
    @GetMapping
    @Operation(summary = "List admin-managed products")
    public ResponseEntity<ApiResponse<PagedResponse<ProductManagementResponse>>> getProducts(
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(required = false) String sellerId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean deleted,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Product> result = products.list(status, sellerId, categoryId, q, deleted, page, size);
        return ResponseEntity.ok(ApiResponse.ok("Admin product listings retrieved",
                PagedResponse.from(result, ProductManagementResponse::from)));
    }

    // Create a new product listing for an existing seller
    @PostMapping
    @Operation(summary = "Create a product listing for an existing seller")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> createProduct(@AuthenticationPrincipal CustomUserDetails admin,
            @Valid @RequestBody AdminProductWriteRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.status(201).body(ApiResponse.ok("Product created", ProductManagementResponse.from(products.create(admin.getId(), request))));
    }

    // Get a product by ID, including inactive or soft-deleted products
    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> getProductById(@PathVariable String productId) {
        return ResponseEntity.ok(ApiResponse.ok("Product retrieved", ProductManagementResponse.from(products.getById(productId))));
    }

    // Update an existing product listing
    @PutMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> updateProduct(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId, @Valid @RequestBody AdminProductWriteRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product updated", ProductManagementResponse.from(products.update(productId, request))));
    }

    // Partially update an existing product listing
    @PatchMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> patchProduct(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId, @Valid @RequestBody AdminProductPatchRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product updated", ProductManagementResponse.from(products.patch(productId, request))));
    }

    // Add a new variant to an existing product
    @PostMapping("/{productId}/variants")
    @Operation(summary = "Add a variant to a product")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> createProductVariant(
            @AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId,
            @Valid @RequestBody AdminProductVariantWriteRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.status(201).body(ApiResponse.ok("Product variant added",
                ProductManagementResponse.from(products.addVariant(productId, request))));
    }

    // Update an existing variant of a product
    @PutMapping("/{productId}/variants/{variantId}")
    @Operation(summary = "Replace one product variant")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> updateProductVariant(
            @AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId, @PathVariable String variantId,
            @Valid @RequestBody AdminProductVariantWriteRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product variant updated",
                ProductManagementResponse.from(products.updateVariant(productId, variantId, request))));
    }

    // Partially update an existing variant of a product
    @PatchMapping("/{productId}/variants/{variantId}")
    @Operation(summary = "Partially update one product variant")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> patchProductVariant(
            @AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId, @PathVariable String variantId,
            @Valid @RequestBody AdminProductVariantPatchRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product variant updated",
                ProductManagementResponse.from(products.patchVariant(productId, variantId, request))));
    }

    // Delete an existing variant of a product
    @DeleteMapping("/{productId}/variants/{variantId}")
    @Operation(summary = "Remove one product variant")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> deleteProductVariant(
            @AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId, @PathVariable String variantId) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product variant removed",
                ProductManagementResponse.from(products.deleteVariant(productId, variantId))));
    }

    // Soft-delete a product listing
    @DeleteMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> softDeleteProduct(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product deactivated", ProductManagementResponse.from(products.softDelete(productId))));
    }

    // Restore a soft-deleted product listing for review
    @PatchMapping("/{productId}/restore")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> restoreProduct(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product restored for review", ProductManagementResponse.from(products.restore(productId))));
    }

    // Permanently delete a previously soft-deleted product listing
    @DeleteMapping("/{productId}/permanent")
    @Operation(summary = "Permanently delete a previously soft-deleted product")
    public ResponseEntity<ApiResponse<String>> permanentlyDeleteProduct(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId) {
        rateLimitGuard.enforce("admin-product-permanent-delete", admin.getId(), 10, Duration.ofMinutes(1));
        products.permanentlyDelete(productId);
        return ResponseEntity.ok(ApiResponse.ok("Product permanently deleted", productId));
    }

    // Approve or reject a seller product listing
    @PatchMapping("/{productId}/review")
    @Operation(summary = "Approve or reject a seller product listing")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> reviewProduct(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String productId,
            @Valid @RequestBody ProductReviewRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        boolean approved = request.approved();
        return ResponseEntity.ok(ApiResponse.ok(approved ? "Product approved" : "Product rejected",
                ProductManagementResponse.from(products.review(productId, approved))));
    }

    // Set product availability (in stock or out of stock)
    @PatchMapping("/{productId}/availability")
    @Operation(summary = "Set product availability")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> updateProductAvailability(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String productId,
            @Valid @RequestBody ProductAvailabilityRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        boolean available = request.available();
        return ResponseEntity.ok(ApiResponse.ok("Product availability updated",
                ProductManagementResponse.from(products.setAvailability(productId, available))));
    }


    // Set product featured state (featured or not featured)
    @PatchMapping("/{productId}/featured")
    @Operation(summary = "Set product featured state")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> updateProductFeaturedStatus(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String productId,
            @Valid @RequestBody ProductFeaturedRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        boolean featured = request.featured();
        return ResponseEntity.ok(ApiResponse.ok("Product featured status updated",
                ProductManagementResponse.from(products.setFeatured(productId, featured))));
    }

    // Assign or reassign a product to one or more collections and subcollections
    @PutMapping("/{productId}/collections")
    @Operation(summary = "Replace a product's collection and subcollection assignments")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> updateProductCollections(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String productId,
            @Valid @RequestBody ProductCollectionAssignmentRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product collections updated",
                ProductManagementResponse.from(products.assignCollections(productId, request))));
    }

    // Upload 1 to 6 product images in one batch
    @PostMapping(
            value = "/{productId}/images",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload 1 to 6 product images in one batch")
    public ResponseEntity<ApiResponse<CountedListResponse<String>>> uploadProductImages(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String productId,
            @RequestPart("files") List<MultipartFile> files) {
        rateLimitGuard.enforce("admin-product-image-upload", admin.getId(), 10, Duration.ofMinutes(1));

        Product updated = products.addImages(productId, files);

        List<String> imageIds = updated.getProductImages().subList(
                        updated.getProductImages().size() - files.size(),
                        updated.getProductImages().size())
                .stream()
                .map(image -> image.getId())
                .toList();

        return ResponseEntity.ok(
                ApiResponse.ok("Product images uploaded", CountedListResponse.from(imageIds)));
    }

    // delete one or more product images by either a single imageID or multiple imageIDs
    @DeleteMapping("/{productId}/images")
    @Operation(summary = "Delete one or more product images", description = "Pass one image ID for a single deletion or multiple IDs for a batch deletion.")
    public ResponseEntity<ApiResponse<CountedListResponse<String>>> deleteProductImages(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String productId,
            @Valid @RequestBody DeleteProductImagesRequest request) {
        rateLimitGuard.enforce("admin-product-image-delete", admin.getId(), 30, Duration.ofMinutes(1));
        products.deleteImages(productId, request.imageIds());

        return ResponseEntity.ok(
                ApiResponse.ok("Product images deleted", CountedListResponse.from(request.imageIds())));
    }
}
