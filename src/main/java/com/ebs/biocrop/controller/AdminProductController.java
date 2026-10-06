package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.ProductManagementResponse;
import com.ebs.biocrop.dto.request.DeleteProductImagesRequest;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.service.AdminProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/products")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin products")
@SecurityRequirement(name = "bearerAuth")
public class AdminProductController {
    private final AdminProductService products;

    public AdminProductController(AdminProductService products) {
        this.products = products;
    }

    @GetMapping
    @Operation(summary = "List product listings by review status")
    public ResponseEntity<ApiResponse<PagedResponse<ProductManagementResponse>>> listForReview(
            @RequestParam(defaultValue = "PENDING_REVIEW") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Product> result = products.listForReview(status, page, size);
        return ResponseEntity.ok(ApiResponse.ok("Admin product listings retrieved",
                PagedResponse.from(result, ProductManagementResponse::from)));
    }

    @PatchMapping("/{id}/review")
    @Operation(summary = "Approve or reject a seller product listing")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> review(@PathVariable String id,
                                                                          @RequestParam boolean approved) {
        return ResponseEntity.ok(ApiResponse.ok(approved ? "Product approved" : "Product rejected",
                ProductManagementResponse.from(products.review(id, approved))));
    }

    @PatchMapping("/{id}/availability")
    @Operation(summary = "Set product availability")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> setAvailability(@PathVariable String id,
                                                                                  @RequestParam boolean available) {
        return ResponseEntity.ok(ApiResponse.ok("Product availability updated",
                ProductManagementResponse.from(products.setAvailability(id, available))));
    }

    @PatchMapping("/{id}/featured")
    @Operation(summary = "Set product featured state")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> setFeatured(@PathVariable String id,
                                                                               @RequestParam boolean featured) {
        return ResponseEntity.ok(ApiResponse.ok("Product featured status updated",
                ProductManagementResponse.from(products.setFeatured(id, featured))));
    }

    @PostMapping(
            value = "/{id}/images",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload 1 to 6 product images in one batch")
    public ResponseEntity<ApiResponse<List<String>>> addImages(
            @PathVariable String id,
            @RequestPart("files") List<MultipartFile> files) {

        Product updated = products.addImages(id, files);

        List<String> imageIds = updated.getProductImages().subList(
                        updated.getProductImages().size() - files.size(),
                        updated.getProductImages().size())
                .stream()
                .map(image -> image.getId())
                .toList();

        return ResponseEntity.ok(
                ApiResponse.ok("Product images uploaded", imageIds));
    }

    @DeleteMapping("/{id}/images/{imageId}")
    @Operation(summary = "Delete a product image")
    public ResponseEntity<ApiResponse<String>> deleteImage(
            @PathVariable String id,
            @PathVariable String imageId) {
        products.deleteImage(id, imageId);

        return ResponseEntity.ok(ApiResponse.ok("Product image deleted", id));
    }

    @DeleteMapping("/{id}/images")
    @Operation(summary = "Delete one or more product images")
    public ResponseEntity<ApiResponse<List<String>>> deleteImages(
            @PathVariable String id,
            @Valid @RequestBody DeleteProductImagesRequest request) {
        products.deleteImages(id, request.imageIds());

        return ResponseEntity.ok(
                ApiResponse.ok("Product images deleted", request.imageIds()));
    }
}
