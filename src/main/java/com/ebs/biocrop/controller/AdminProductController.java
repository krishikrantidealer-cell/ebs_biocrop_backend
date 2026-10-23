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

    @GetMapping
    @Operation(summary = "List admin-managed products")
    public ResponseEntity<ApiResponse<PagedResponse<ProductManagementResponse>>> listAdminProducts(
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(required = false) String sellerId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean deleted,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Product> result = products.listAdminProducts(status, sellerId, categoryId, q, deleted, page, size);
        return ResponseEntity.ok(ApiResponse.ok("Admin product listings retrieved",
                PagedResponse.from(result, ProductManagementResponse::from)));
    }

    @PostMapping
    @Operation(summary = "Create a product listing for an existing seller")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> createAdminProduct(@AuthenticationPrincipal CustomUserDetails admin,
            @Valid @RequestBody AdminProductWriteRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.status(201).body(ApiResponse.ok("Product created", ProductManagementResponse.from(products.createAdminProduct(admin.getId(), request))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> getAdminProductById(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("Product retrieved", ProductManagementResponse.from(products.getAdminProductById(id))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> updateAdminProduct(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String id, @Valid @RequestBody AdminProductWriteRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product updated", ProductManagementResponse.from(products.updateAdminProduct(id, request))));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> patchAdminProduct(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String id, @Valid @RequestBody AdminProductPatchRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product updated", ProductManagementResponse.from(products.patchAdminProduct(id, request))));
    }

    @PostMapping("/{id}/variants")
    @Operation(summary = "Add a variant to a product")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> addAdminProductVariant(
            @AuthenticationPrincipal CustomUserDetails admin, @PathVariable String id,
            @Valid @RequestBody AdminProductVariantWriteRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.status(201).body(ApiResponse.ok("Product variant added",
                ProductManagementResponse.from(products.addAdminProductVariant(id, request))));
    }

    @PutMapping("/{id}/variants/{variantId}")
    @Operation(summary = "Replace one product variant")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> updateAdminProductVariant(
            @AuthenticationPrincipal CustomUserDetails admin, @PathVariable String id, @PathVariable String variantId,
            @Valid @RequestBody AdminProductVariantWriteRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product variant updated",
                ProductManagementResponse.from(products.updateAdminProductVariant(id, variantId, request))));
    }

    @PatchMapping("/{id}/variants/{variantId}")
    @Operation(summary = "Partially update one product variant")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> patchAdminProductVariant(
            @AuthenticationPrincipal CustomUserDetails admin, @PathVariable String id, @PathVariable String variantId,
            @Valid @RequestBody AdminProductVariantPatchRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product variant updated",
                ProductManagementResponse.from(products.patchAdminProductVariant(id, variantId, request))));
    }

    @DeleteMapping("/{id}/variants/{variantId}")
    @Operation(summary = "Remove one product variant")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> deleteAdminProductVariant(
            @AuthenticationPrincipal CustomUserDetails admin, @PathVariable String id, @PathVariable String variantId) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product variant removed",
                ProductManagementResponse.from(products.deleteAdminProductVariant(id, variantId))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> softDeleteAdminProduct(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String id) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product deactivated", ProductManagementResponse.from(products.softDeleteAdminProduct(id))));
    }

    @PatchMapping("/{id}/restore")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> restoreAdminProduct(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String id) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product restored for review", ProductManagementResponse.from(products.restoreAdminProduct(id))));
    }

    @DeleteMapping("/{id}/permanent")
    @Operation(summary = "Permanently delete a previously soft-deleted product")
    public ResponseEntity<ApiResponse<String>> permanentlyDeleteAdminProduct(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String id) {
        rateLimitGuard.enforce("admin-product-permanent-delete", admin.getId(), 10, Duration.ofMinutes(1));
        products.permanentlyDeleteAdminProduct(id);
        return ResponseEntity.ok(ApiResponse.ok("Product permanently deleted", id));
    }

    @PatchMapping("/{id}/review")
    @Operation(summary = "Approve or reject a seller product listing")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> reviewAdminProduct(
                                                                          @AuthenticationPrincipal CustomUserDetails admin,
                                                                          @PathVariable String id,
                                                                          @RequestParam boolean approved) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok(approved ? "Product approved" : "Product rejected",
                ProductManagementResponse.from(products.reviewAdminProduct(id, approved))));
    }

    @PatchMapping("/{id}/availability")
    @Operation(summary = "Set product availability")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> setAdminProductAvailability(
                                                                                  @AuthenticationPrincipal CustomUserDetails admin,
                                                                                  @PathVariable String id,
                                                                                  @RequestParam boolean available) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product availability updated",
                ProductManagementResponse.from(products.setAdminProductAvailability(id, available))));
    }

    @PatchMapping("/{id}/featured")
    @Operation(summary = "Set product featured state")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> setAdminProductFeatured(
                                                                               @AuthenticationPrincipal CustomUserDetails admin,
                                                                               @PathVariable String id,
                                                                               @RequestParam boolean featured) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product featured status updated",
                ProductManagementResponse.from(products.setAdminProductFeatured(id, featured))));
    }

    @PatchMapping("/{id}/collections")
    @Operation(summary = "Assign a product to collections and subcollections")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> assignAdminProductCollections(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String id,
            @Valid @RequestBody ProductCollectionAssignmentRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product collections updated",
                ProductManagementResponse.from(products.assignAdminProductCollections(id, request))));
    }

    @PostMapping(
            value = "/{id}/images",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload 1 to 6 product images in one batch")
    public ResponseEntity<ApiResponse<CountedListResponse<String>>> addAdminProductImages(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String id,
            @RequestPart("files") List<MultipartFile> files) {
        rateLimitGuard.enforce("admin-product-image-upload", admin.getId(), 10, Duration.ofMinutes(1));

        Product updated = products.addAdminProductImages(id, files);

        List<String> imageIds = updated.getProductImages().subList(
                        updated.getProductImages().size() - files.size(),
                        updated.getProductImages().size())
                .stream()
                .map(image -> image.getId())
                .toList();

        return ResponseEntity.ok(
                ApiResponse.ok("Product images uploaded", CountedListResponse.from(imageIds)));
    }

    @DeleteMapping("/{id}/images/{imageId}")
    @Operation(summary = "Delete a product image")
    public ResponseEntity<ApiResponse<String>> deleteAdminProductImage(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String id,
            @PathVariable String imageId) {
        rateLimitGuard.enforce("admin-product-image-delete", admin.getId(), 30, Duration.ofMinutes(1));
        products.deleteAdminProductImage(id, imageId);

        return ResponseEntity.ok(ApiResponse.ok("Product image deleted", id));
    }

    @DeleteMapping("/{id}/images")
    @Operation(summary = "Delete one or more product images")
    public ResponseEntity<ApiResponse<CountedListResponse<String>>> deleteAdminProductImages(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String id,
            @Valid @RequestBody DeleteProductImagesRequest request) {
        rateLimitGuard.enforce("admin-product-image-delete", admin.getId(), 30, Duration.ofMinutes(1));
        products.deleteAdminProductImages(id, request.imageIds());

        return ResponseEntity.ok(
                ApiResponse.ok("Product images deleted", CountedListResponse.from(request.imageIds())));
    }
}
