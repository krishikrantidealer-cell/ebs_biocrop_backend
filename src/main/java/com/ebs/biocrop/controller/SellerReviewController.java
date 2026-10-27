package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.ReviewResponse;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/seller")
@PreAuthorize("hasRole('SELLER')")
@Tag(name = "Seller Reviews", description = "Seller reviews monitoring for own products")
@SecurityRequirement(name = "bearerAuth")
public class SellerReviewController {

    private final ReviewService reviewService;

    public SellerReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/reviews")
    @Operation(summary = "List all reviews for products owned by the authenticated seller")
    public ResponseEntity<ApiResponse<PagedResponse<ReviewResponse>>> listSellerReviews(
            @AuthenticationPrincipal CustomUserDetails seller,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) Integer rating,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int validPage = Math.max(0, page);
        int validSize = Math.min(50, Math.max(1, size));
        PageRequest pageRequest = PageRequest.of(validPage, validSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<ReviewResponse> result = reviewService.listSellerReviews(seller.getId(), productId, rating, pageRequest);
        return ResponseEntity.ok(ApiResponse.ok("Seller reviews retrieved successfully", PagedResponse.from(result, r -> r)));
    }

    @GetMapping("/products/{productId}/reviews")
    @Operation(summary = "List reviews for a specific product owned by the authenticated seller")
    public ResponseEntity<ApiResponse<PagedResponse<ReviewResponse>>> listSellerProductReviews(
            @AuthenticationPrincipal CustomUserDetails seller,
            @PathVariable String productId,
            @RequestParam(required = false) Integer rating,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int validPage = Math.max(0, page);
        int validSize = Math.min(50, Math.max(1, size));
        PageRequest pageRequest = PageRequest.of(validPage, validSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<ReviewResponse> result = reviewService.listSellerReviews(seller.getId(), productId, rating, pageRequest);
        return ResponseEntity.ok(ApiResponse.ok("Product reviews retrieved successfully", PagedResponse.from(result, r -> r)));
    }
}
