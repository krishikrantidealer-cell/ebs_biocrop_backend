package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.ProductManagementResponse;
import com.ebs.biocrop.dto.response.CountedListResponse;
import com.ebs.biocrop.dto.request.DeleteProductImagesRequest;
import com.ebs.biocrop.dto.request.DeleteProductsRequest;
import com.ebs.biocrop.dto.request.AdminProductWriteRequest;
import com.ebs.biocrop.dto.request.AdminProductPatchRequest;
import com.ebs.biocrop.dto.request.AdminProductUpdateRequest;
import com.ebs.biocrop.dto.request.AdminProductVariantPatchRequest;
import com.ebs.biocrop.dto.request.AdminProductVariantWriteRequest;
import com.ebs.biocrop.dto.request.ProductCollectionAssignmentRequest;
import com.ebs.biocrop.dto.request.ProductReviewRequest;
import com.ebs.biocrop.dto.request.ProductAvailabilityRequest;
import com.ebs.biocrop.dto.request.ProductFeaturedRequest;
import com.ebs.biocrop.dto.request.ProductVariantDefaultRequest;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.service.AdminProductService;
import com.ebs.biocrop.service.RateLimitGuard;
import com.ebs.biocrop.service.SellerShopNameLookup;
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
import java.util.Map;
import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolation;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import com.ebs.biocrop.exception.AppException;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/admin/products")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin products")
@SecurityRequirement(name = "bearerAuth")
public class AdminProductController {
    private final AdminProductService products;
    private final RateLimitGuard rateLimitGuard;
    private final SellerShopNameLookup sellerNames;
    private final com.ebs.biocrop.service.GcsImageStorageService imageStorage;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminProductController(AdminProductService products, RateLimitGuard rateLimitGuard,
                                  SellerShopNameLookup sellerNames,
                                  com.ebs.biocrop.service.GcsImageStorageService imageStorage,
                                  ObjectMapper objectMapper, Validator validator) {
        this.products = products;
        this.rateLimitGuard = rateLimitGuard;
        this.sellerNames = sellerNames;
        this.imageStorage = imageStorage;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public AdminProductController(AdminProductService products, RateLimitGuard rateLimitGuard,
                                  SellerShopNameLookup sellerNames) {
        this(products, rateLimitGuard, sellerNames, null, new ObjectMapper(),
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
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
            @RequestParam(required = false) Boolean available,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Product> result = products.list(status, sellerId, categoryId, q, deleted, available, page, size);
        Map<String, String> currentNames = sellerNames.findCurrentShopNames(result.getContent().stream()
                .map(Product::getSellerId).distinct().toList());
        return ResponseEntity.ok(ApiResponse.ok("Admin product listings retrieved",
                PagedResponse.from(result, product -> ProductManagementResponse.from(
                        product, currentNames.get(product.getSellerId()), imageStorage))));
    }

    // Create a new product listing for an existing seller (JSON payload, no images)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a product listing for an existing seller (JSON)")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> createProduct(@AuthenticationPrincipal CustomUserDetails admin,
            @Valid @RequestBody AdminProductWriteRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.status(201).body(ApiResponse.ok("Product created", managementResponse(products.create(admin.getId(), request))));
    }

    // Create a new product listing with optional product images in one request (Multipart form-data)
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Create a product listing with product images in one request (Multipart)",
               description = "Accepts product payload under 'product' or 'data' part (JSON string) and 1 to 6 image files under 'files' or 'images'.")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> createProductMultipart(
            @AuthenticationPrincipal CustomUserDetails admin,
            MultipartHttpServletRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        AdminProductWriteRequest productRequest = parseAndValidateProductRequest(request);
        if (productRequest.getImages() != null && !productRequest.getImages().isEmpty()) {
            throw new AppException(
                    "Send uploaded image files as multipart parts; image URLs are not accepted during multipart creation",
                    HttpStatus.BAD_REQUEST);
        }
        List<MultipartFile> files = extractImageFiles(request);
        if (!files.isEmpty()) {
            rateLimitGuard.enforce("admin-product-image-upload", admin.getId(), 10, Duration.ofMinutes(1));
        }
        return ResponseEntity.status(201).body(ApiResponse.ok("Product created",
                managementResponse(products.create(admin.getId(), productRequest, files))));
    }

    // Get a product by ID, including inactive or soft-deleted products
    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> getProductById(@PathVariable String productId) {
        return ResponseEntity.ok(ApiResponse.ok("Product retrieved", managementResponse(products.getById(productId))));
    }

    // Unified Product Details Update (handles both complete details update and partial field updates)
    @PatchMapping("/{productId}")
    @Operation(summary = "Update product details (full or partial)",
               description = "Single unified API to update product details. Supports updating all fields (complete update) or any single field / subset of fields (partial update).")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> updateProduct(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String productId,
            @Valid @RequestBody AdminProductUpdateRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product updated", managementResponse(products.update(productId, request))));
    }

    // Add a new variant to an existing product
    @PostMapping("/{productId}/variants")
    @Operation(summary = "Add a variant to a product")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> createProductVariant(
            @AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId,
            @Valid @RequestBody AdminProductVariantWriteRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.status(201).body(ApiResponse.ok("Product variant added",
                managementResponse(products.addVariant(productId, request))));
    }

    // Update an existing variant of a product
    @PutMapping("/{productId}/variants/{variantId}")
    @Operation(summary = "Replace one product variant")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> updateProductVariant(
            @AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId, @PathVariable String variantId,
            @Valid @RequestBody AdminProductVariantWriteRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product variant updated",
                managementResponse(products.updateVariant(productId, variantId, request))));
    }

    // Partially update an existing variant of a product
    @PatchMapping("/{productId}/variants/{variantId}")
    @Operation(summary = "Partially update one product variant")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> patchProductVariant(
            @AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId, @PathVariable String variantId,
            @Valid @RequestBody AdminProductVariantPatchRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product variant updated",
                managementResponse(products.patchVariant(productId, variantId, request))));
    }

    // Set or unset a variant as the default for a product
    @PatchMapping("/{productId}/variants/{variantId}/default")
    @Operation(summary = "Set a product variant as default or non-default")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> setDefaultVariant(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String productId,
            @PathVariable String variantId,
            @Valid @RequestBody ProductVariantDefaultRequest request) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 60, Duration.ofMinutes(1));
        boolean isDefault = Boolean.TRUE.equals(request.isDefault());
        return ResponseEntity.ok(ApiResponse.ok(
                isDefault ? "Variant marked as default" : "Variant unmarked as default",
                managementResponse(products.setDefaultVariant(productId, variantId, isDefault))));
    }

    // Delete an existing variant of a product
    @DeleteMapping("/{productId}/variants/{variantId}")
    @Operation(summary = "Remove one product variant")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> deleteProductVariant(
            @AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId, @PathVariable String variantId) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product variant removed",
                managementResponse(products.deleteVariant(productId, variantId))));
    }

    // Soft-delete a product listing
    @DeleteMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> softDeleteProduct(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product deactivated", managementResponse(products.softDelete(productId))));
    }

    // Restore a soft-deleted product listing for review
    @PatchMapping("/{productId}/restore")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> restoreProduct(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId) {
        rateLimitGuard.enforce("admin-product-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Product restored for review", managementResponse(products.restore(productId))));
    }

    // Permanently delete one or more products (unified single & bulk endpoint)
    @DeleteMapping("/permanent")
    @Operation(summary = "Permanently delete one or more products",
               description = "Pass one product ID to delete a single product, or multiple IDs to delete in bulk.")
    public ResponseEntity<ApiResponse<CountedListResponse<String>>> permanentlyDeleteProducts(
            @AuthenticationPrincipal CustomUserDetails admin,
            @Valid @RequestBody DeleteProductsRequest request) {
        rateLimitGuard.enforce("admin-product-permanent-delete", admin.getId(), 30, Duration.ofMinutes(1));
        List<String> deleted = products.permanentlyDeleteMany(request.productIds());
        return ResponseEntity.ok(ApiResponse.ok("Products permanently deleted", CountedListResponse.from(deleted)));
    }

    // Permanently delete a single product listing (delegates to unified handler)
    @DeleteMapping("/{productId}/permanent")
    @Operation(summary = "Permanently delete a product listing")
    public ResponseEntity<ApiResponse<String>> permanentlyDeleteProduct(@AuthenticationPrincipal CustomUserDetails admin, @PathVariable String productId) {
        rateLimitGuard.enforce("admin-product-permanent-delete", admin.getId(), 30, Duration.ofMinutes(1));
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
                managementResponse(products.review(productId, approved))));
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
                managementResponse(products.setAvailability(productId, available))));
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
                managementResponse(products.setFeatured(productId, featured))));
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
                managementResponse(products.assignCollections(productId, request))));
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


    private AdminProductWriteRequest parseAndValidateProductRequest(MultipartHttpServletRequest request) {
        String rawJson = null;
        String param = request.getParameter("product");
        if (param == null || param.isBlank()) {
            param = request.getParameter("data");
        }
        if (param != null && !param.isBlank()) {
            rawJson = param;
        } else {
            MultipartFile part = request.getFile("product");
            if (part == null) {
                part = request.getFile("data");
            }
            if (part != null && !part.isEmpty()) {
                try {
                    rawJson = new String(part.getBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new AppException("Failed to read product JSON payload", HttpStatus.BAD_REQUEST);
                }
            }
        }

        if (rawJson == null || rawJson.isBlank()) {
            throw new AppException("Product payload ('product' or 'data') is required", HttpStatus.BAD_REQUEST);
        }

        AdminProductWriteRequest writeRequest;
        try {
            writeRequest = objectMapper.readValue(rawJson, AdminProductWriteRequest.class);
        } catch (Exception e) {
            throw new AppException("Invalid product JSON payload: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }

        Set<ConstraintViolation<AdminProductWriteRequest>> violations = validator.validate(writeRequest);
        if (!violations.isEmpty()) {
            ConstraintViolation<AdminProductWriteRequest> firstViolation = violations.iterator().next();
            throw new AppException(firstViolation.getPropertyPath() + ": " + firstViolation.getMessage(), HttpStatus.BAD_REQUEST);
        }

        return writeRequest;
    }

    private List<MultipartFile> extractImageFiles(MultipartHttpServletRequest request) {
        List<MultipartFile> candidate = new ArrayList<>();
        List<MultipartFile> files = request.getFiles("files");
        if (files != null) candidate.addAll(files);
        List<MultipartFile> images = request.getFiles("images");
        if (images != null) candidate.addAll(images);
        return candidate.stream().filter(file -> file != null && !file.isEmpty()).toList();
    }

    private ProductManagementResponse managementResponse(Product product) {
        String currentName = sellerNames.findCurrentShopNames(java.util.Collections.singletonList(product.getSellerId()))
                .get(product.getSellerId());
        return ProductManagementResponse.from(product, currentName, imageStorage);
    }
}
