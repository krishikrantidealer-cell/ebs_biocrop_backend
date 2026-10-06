package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.CartItemRequest;
import com.ebs.biocrop.dto.request.CartItemUpdateRequest;
import com.ebs.biocrop.dto.request.CartSyncRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.CartCountResponse;
import com.ebs.biocrop.dto.response.CartResponse;
import com.ebs.biocrop.service.CartService;
import com.ebs.biocrop.service.RateLimitGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "Cart", description = "Customer cart operations.")
@SecurityRequirement(name = "bearerAuth")
public class CartController {

    private final CartService cartService;
    private final RateLimitGuard rateLimitGuard;

    public CartController(CartService cartService, RateLimitGuard rateLimitGuard) {
        this.cartService = cartService;
        this.rateLimitGuard = rateLimitGuard;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart(Authentication authentication) {
        String phoneNumber = authentication.getName();
        CartResponse cart = cartService.getCart(phoneNumber);
        return ResponseEntity.ok(ApiResponse.ok("Cart retrieved successfully", cart));
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<CartCountResponse>> getCartCount(Authentication authentication) {
        String phoneNumber = authentication.getName();
        CartCountResponse count = cartService.getCartCount(phoneNumber);
        return ResponseEntity.ok(ApiResponse.ok("Cart count retrieved successfully", count));
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addToCart(
            Authentication authentication,
            @Valid @RequestBody CartItemRequest request) {
        String phoneNumber = authentication.getName();
        rateLimitGuard.enforce("cart-write", phoneNumber, 60, Duration.ofMinutes(1));
        CartResponse cart = cartService.addToCart(phoneNumber, request);
        return ResponseEntity.ok(ApiResponse.ok("Item added to cart successfully", cart));
    }

    @PutMapping("/items/{variantId}")
    public ResponseEntity<ApiResponse<CartResponse>> updateItemQuantity(
            Authentication authentication,
            @PathVariable String variantId,
            @Valid @RequestBody CartItemUpdateRequest request) {
        String phoneNumber = authentication.getName();
        rateLimitGuard.enforce("cart-write", phoneNumber, 60, Duration.ofMinutes(1));
        CartResponse cart = cartService.updateQuantity(phoneNumber, variantId, request.getQuantity());
        return ResponseEntity.ok(ApiResponse.ok("Cart item quantity updated successfully", cart));
    }

    @DeleteMapping("/items/{variantId}")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(
            Authentication authentication,
            @PathVariable String variantId) {
        String phoneNumber = authentication.getName();
        rateLimitGuard.enforce("cart-write", phoneNumber, 60, Duration.ofMinutes(1));
        CartResponse cart = cartService.removeItem(phoneNumber, variantId);
        return ResponseEntity.ok(ApiResponse.ok("Item removed from cart successfully", cart));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<CartResponse>> clearCart(Authentication authentication) {
        String phoneNumber = authentication.getName();
        rateLimitGuard.enforce("cart-write", phoneNumber, 60, Duration.ofMinutes(1));
        CartResponse cart = cartService.clearCart(phoneNumber);
        return ResponseEntity.ok(ApiResponse.ok("Cart cleared successfully", cart));
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<CartResponse>> syncCart(
            Authentication authentication,
            @Valid @RequestBody CartSyncRequest request) {
        String phoneNumber = authentication.getName();
        rateLimitGuard.enforce("cart-write", phoneNumber, 60, Duration.ofMinutes(1));
        rateLimitGuard.enforce("cart-sync", phoneNumber, 10, Duration.ofMinutes(1));
        CartResponse cart = cartService.syncCart(phoneNumber, request);
        return ResponseEntity.ok(ApiResponse.ok("Cart synchronized successfully", cart));
    }

}
