package com.ebs.biocrop.dto.response;

import java.util.List;

public record ProductReviewsResponse(
        String productId,
        Double averageRating,
        Long totalReviews,
        Long count,
        List<ReviewResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static ProductReviewsResponse of(
            String productId,
            Double averageRating,
            Long totalReviews,
            PagedResponse<ReviewResponse> paged
    ) {
        return new ProductReviewsResponse(
                productId,
                averageRating,
                totalReviews,
                totalReviews,
                paged.content(),
                paged.page(),
                paged.size(),
                paged.totalElements(),
                paged.totalPages(),
                paged.first(),
                paged.last()
        );
    }
}
