package com.ebs.biocrop.dto.response;

public record ReviewEligibilityResponse(
        boolean eligible,
        boolean isVerifiedBuyer,
        boolean hasReviewed,
        String existingReviewId,
        String message
) { }
