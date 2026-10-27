package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.request.CreateReviewRequest;
import com.ebs.biocrop.dto.response.AdminReviewResponse;
import com.ebs.biocrop.dto.response.ProductReviewsResponse;
import com.ebs.biocrop.dto.response.ReviewEligibilityResponse;
import com.ebs.biocrop.dto.response.ReviewResponse;
import com.ebs.biocrop.dto.response.ReviewSummaryResponse;
import com.ebs.biocrop.dto.response.SellerReviewReportResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ReviewService {

    // Customer & Public operations
    ReviewEligibilityResponse checkEligibility(String customerId, String productId);

    ReviewResponse createReview(String customerId, String productId, CreateReviewRequest request);

    ReviewResponse getCustomerReview(String customerId, String productId);

    ProductReviewsResponse listProductReviews(String productId, Integer rating, Boolean hasMedia, Pageable pageable);

    ReviewSummaryResponse getProductReviewSummary(String productId);

    // Seller operations
    Page<ReviewResponse> listSellerReviews(String sellerId, String productId, Integer rating, Pageable pageable);

    // Admin operations
    Page<AdminReviewResponse> listAdminReviews(String productId, String search, Integer rating, Pageable pageable);

    SellerReviewReportResponse getSellerReviewReport(String sellerId);

    void adminDeleteReview(String reviewId);
}
