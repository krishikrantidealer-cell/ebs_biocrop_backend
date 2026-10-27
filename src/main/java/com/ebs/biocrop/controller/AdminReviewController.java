package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.AdminReviewResponse;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.SellerReviewReportResponse;
import com.ebs.biocrop.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/reviews")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Reviews", description = "Admin product review management and permanent moderation")
@SecurityRequirement(name = "bearerAuth")
public class AdminReviewController {

    private final ReviewService reviewService;

    public AdminReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    @Operation(summary = "List all reviews with optional filters for product ID, product title search, or rating")
    public ResponseEntity<ApiResponse<PagedResponse<AdminReviewResponse>>> listAdminReviews(
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer rating,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int validPage = Math.max(0, page);
        int validSize = Math.min(50, Math.max(1, size));
        PageRequest pageRequest = PageRequest.of(validPage, validSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<AdminReviewResponse> result = reviewService.listAdminReviews(productId, search, rating, pageRequest);
        return ResponseEntity.ok(ApiResponse.ok("Admin reviews retrieved successfully", PagedResponse.from(result, r -> r)));
    }

    @GetMapping("/seller-report")
    @Operation(summary = "Get aggregate review performance for a seller's products")
    public ResponseEntity<ApiResponse<SellerReviewReportResponse>> getSellerReviewReport(
            @RequestParam String sellerId) {
        SellerReviewReportResponse report = reviewService.getSellerReviewReport(sellerId);
        return ResponseEntity.ok(ApiResponse.ok("Seller review report retrieved successfully", report));
    }

    @DeleteMapping("/{reviewId}")
    @Operation(summary = "Permanently delete a review from the database and recalculate product rating")
    public ResponseEntity<ApiResponse<Void>> deleteReviewPermanently(@PathVariable String reviewId) {
        reviewService.adminDeleteReview(reviewId);
        return ResponseEntity.ok(ApiResponse.ok("Review permanently deleted successfully", null));
    }
}
