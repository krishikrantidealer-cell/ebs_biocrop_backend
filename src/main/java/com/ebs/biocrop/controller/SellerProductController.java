package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.ProductManagementResponse;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.SellerProductService;
import com.ebs.biocrop.service.SellerShopNameLookup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/seller/products")
@PreAuthorize("hasRole('SELLER')")
@Tag(name = "Seller products", description = "Read-only monitoring of the authenticated seller's product listings.")
@SecurityRequirement(name = "bearerAuth")
public class SellerProductController {
    private final SellerProductService sellerProducts;
    private final SellerShopNameLookup sellerNames;
    private final com.ebs.biocrop.service.GcsImageStorageService imageStorage;

    @org.springframework.beans.factory.annotation.Autowired
    public SellerProductController(SellerProductService sellerProducts, SellerShopNameLookup sellerNames,
                                   com.ebs.biocrop.service.GcsImageStorageService imageStorage) {
        this.sellerProducts = sellerProducts;
        this.sellerNames = sellerNames;
        this.imageStorage = imageStorage;
    }

    public SellerProductController(SellerProductService sellerProducts, SellerShopNameLookup sellerNames) {
        this(sellerProducts, sellerNames, null);
    }

    // List the authenticated seller's product listings (Read-Only)
    @GetMapping
    @Operation(summary = "List the authenticated seller's product listings")
    public ResponseEntity<ApiResponse<PagedResponse<ProductManagementResponse>>> list(
            @AuthenticationPrincipal CustomUserDetails seller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Product> result = sellerProducts.list(seller.getId(), page, size);
        Map<String, String> currentNames = sellerNames.findCurrentShopNames(result.getContent().stream()
                .map(Product::getSellerId).distinct().toList());
        return ResponseEntity.ok(ApiResponse.ok("Seller listings retrieved",
                PagedResponse.from(result, product -> ProductManagementResponse.from(
                        product, currentNames.get(product.getSellerId()), imageStorage))));
    }

    // Get a specific product listing by ID for the authenticated seller (Read-Only)
    @GetMapping("/{id}")
    @Operation(summary = "Get a product listing by ID for the authenticated seller")
    public ResponseEntity<ApiResponse<ProductManagementResponse>> get(
            @AuthenticationPrincipal CustomUserDetails seller,
            @PathVariable String id) {
        Product product = sellerProducts.get(seller.getId(), id);
        String currentName = sellerNames.findCurrentShopNames(java.util.Collections.singletonList(product.getSellerId()))
                .get(product.getSellerId());
        return ResponseEntity.ok(ApiResponse.ok("Seller listing retrieved",
                ProductManagementResponse.from(product, currentName, imageStorage)));
    }
}
