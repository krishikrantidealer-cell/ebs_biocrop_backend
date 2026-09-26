package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.WishlistResponse;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.WishlistService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wishlist")
@PreAuthorize("hasRole('CUSTOMER')")
public class WishlistController {

    private final WishlistService wishlistService;

    public WishlistController(WishlistService wishlistService) {
        this.wishlistService = wishlistService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<WishlistResponse>> getWishlist(
            @AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Wishlist retrieved successfully",
                wishlistService.getWishlist(user.getId())));
    }

    @PutMapping("/items/{variantId}")
    public ResponseEntity<ApiResponse<WishlistResponse>> addVariant(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable String variantId) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Product variant saved to wishlist",
                wishlistService.addVariant(user.getId(), variantId)));
    }

    @DeleteMapping("/items/{variantId}")
    public ResponseEntity<ApiResponse<WishlistResponse>> removeVariant(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable String variantId) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Product variant removed from wishlist",
                wishlistService.removeVariant(user.getId(), variantId)));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<WishlistResponse>> clearWishlist(
            @AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Wishlist cleared successfully",
                wishlistService.clearWishlist(user.getId())));
    }
}
