package com.ebs.biocrop.dto.response;

import java.util.Map;

public record ReviewSummaryResponse(
        String productId,
        Double averageRating,
        Long totalReviews,
        Long count,
        Map<String, Long> starDistribution,
        Map<String, Long> ratingDistribution,
        Long withMediaCount
) { }
