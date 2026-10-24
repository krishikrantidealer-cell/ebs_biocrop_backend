package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.ProductManagementResponse;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.SellerProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/seller/products")
@PreAuthorize("hasRole('SELLER')")
@Tag(name = "Seller products", description = "Read-only monitoring of the authenticated seller's product listings.")
@SecurityRequirement(name = "bearerAuth")
public class SellerProductController {
    private final SellerProductService sellerProducts;

    public SellerProductController(SellerProductService sellerProducts) {
        this.sellerProducts = sellerProducts;
    }

    // List the authenticated seller's product listings.
    @GetMapping
    @Operation(summary = "List the authenticated seller's product listings")
    public ResponseEntity<ApiResponse<PagedResponse<ProductManagementResponse>>> list(
            @AuthenticationPrincipal CustomUserDetails seller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Product> result = sellerProducts.list(seller.getId(), page, size);
        return ResponseEntity.ok(ApiResponse.ok("Seller listings retrieved",
                PagedResponse.from(result, ProductManagementResponse::from)));
    }

}
