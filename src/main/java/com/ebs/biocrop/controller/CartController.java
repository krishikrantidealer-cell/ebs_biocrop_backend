package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.CartItemRequest;
import com.ebs.biocrop.dto.request.CartItemUpdateRequest;
import com.ebs.biocrop.dto.request.CartSyncRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.CartCountResponse;
import com.ebs.biocrop.dto.response.CartResponse;
import com.ebs.biocrop.dto.response.CheckoutSummaryResponse;
import com.ebs.biocrop.service.CartService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
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
        CartResponse cart = cartService.addToCart(phoneNumber, request);
        return ResponseEntity.ok(ApiResponse.ok("Item added to cart successfully", cart));
    }

    @PutMapping("/items/{variationCode}")
    public ResponseEntity<ApiResponse<CartResponse>> updateItemQuantity(
            Authentication authentication,
            @PathVariable String variationCode,
            @Valid @RequestBody CartItemUpdateRequest request) {
        String phoneNumber = authentication.getName();
        CartResponse cart = cartService.updateQuantity(phoneNumber, variationCode, request.getQuantity());
        return ResponseEntity.ok(ApiResponse.ok("Cart item quantity updated successfully", cart));
    }

    @DeleteMapping("/items/{variationCode}")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(
            Authentication authentication,
            @PathVariable String variationCode) {
        String phoneNumber = authentication.getName();
        CartResponse cart = cartService.removeItem(phoneNumber, variationCode);
        return ResponseEntity.ok(ApiResponse.ok("Item removed from cart successfully", cart));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<CartResponse>> clearCart(Authentication authentication) {
        String phoneNumber = authentication.getName();
        CartResponse cart = cartService.clearCart(phoneNumber);
        return ResponseEntity.ok(ApiResponse.ok("Cart cleared successfully", cart));
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<CartResponse>> syncCart(
            Authentication authentication,
            @Valid @RequestBody CartSyncRequest request) {
        String phoneNumber = authentication.getName();
        CartResponse cart = cartService.syncCart(phoneNumber, request);
        return ResponseEntity.ok(ApiResponse.ok("Cart synchronized successfully", cart));
    }

    @GetMapping("/checkout-summary")
    public ResponseEntity<ApiResponse<CheckoutSummaryResponse>> getCheckoutSummary(Authentication authentication) {
        String phoneNumber = authentication.getName();
        CheckoutSummaryResponse summary = cartService.getCheckoutSummary(phoneNumber);
        return ResponseEntity.ok(ApiResponse.ok("Checkout summary prepared successfully", summary));
    }
}