package com.ebs.biocrop.dto.response;

import java.util.List;
import java.util.Map;

/** Aggregate seller review performance; intentionally contains no individual review or customer data. */
public record SellerReviewReportResponse(
        String sellerId,
        long totalProducts,
        long totalReviews,
        long approvedReviews,
        double averageRating,
        Map<String, Long> reviewStatusCounts,
        Map<String, Long> ratingDistribution,
        List<ProductPerformance> products) {

    public record ProductPerformance(
            String productId,
            String productCode,
            String sku,
            String title,
            String productStatus,
            Boolean isAvailable,
            long totalReviews,
            long approvedReviews,
            double averageRating,
            Map<String, Long> reviewStatusCounts,
            Map<String, Long> ratingDistribution) {
    }
}
