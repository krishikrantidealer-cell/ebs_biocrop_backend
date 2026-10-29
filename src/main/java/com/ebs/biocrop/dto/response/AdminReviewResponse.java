package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Review;

import java.time.LocalDateTime;
import java.util.List;

public record AdminReviewResponse(
        String id,
        String productId,
        String productTitle,
        String sellerId,
        String customerId,
        String firstName,
        String lastName,
        String phoneNumber,
        Integer rating,
        String title,
        String comment,
        List<String> images,
        String video,
        Boolean isAnonymous,
        Boolean isVerifiedBuyer,
        String status,
        Boolean isDeleted,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public AdminReviewResponse(String id, String productId, String productTitle, String sellerId,
            String customerId, String firstName, String lastName, String phoneNumber, Integer rating,
            String title, String comment, List<String> images, String video, Boolean isAnonymous,
            Boolean isVerifiedBuyer, String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, productId, productTitle, sellerId, customerId, firstName, lastName, phoneNumber,
                rating, title, comment, images, video, isAnonymous, isVerifiedBuyer, status, false,
                createdAt, updatedAt);
    }

    public static AdminReviewResponse from(Review review, String productTitle) {
        return new AdminReviewResponse(
                review.getId(),
                review.getProductId(),
                productTitle != null ? productTitle : "Product",
                review.getSellerId(),
                review.getCustomerId(),
                review.getFirstName(),
                review.getLastName(),
                review.getPhoneNumber(),
                review.getRating(),
                review.getTitle(),
                review.getComment(),
                review.getImages(),
                review.getVideo(),
                review.getIsAnonymous(),
                review.getIsVerifiedBuyer(),
                review.getStatus(),
                review.getIsDeleted(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }
}
