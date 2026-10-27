package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Review;

import java.time.LocalDateTime;
import java.util.List;

public record ReviewResponse(
        String id,
        String productId,
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
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ReviewResponse forPublic(Review review) {
        String fName = review.getFirstName();
        String lName = review.getLastName();

        if (Boolean.TRUE.equals(review.getIsAnonymous())) {
            fName = mask(fName);
            lName = mask(lName);
        }

        return new ReviewResponse(
                review.getId(),
                review.getProductId(),
                null, // customerId hidden for public privacy
                fName,
                lName,
                null, // phoneNumber strictly hidden for public privacy
                review.getRating(),
                review.getTitle(),
                review.getComment(),
                review.getImages(),
                review.getVideo(),
                review.getIsAnonymous(),
                review.getIsVerifiedBuyer(),
                review.getStatus(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }

    public static ReviewResponse forCustomer(Review review) {
        return new ReviewResponse(
                review.getId(),
                review.getProductId(),
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
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }

    public static ReviewResponse forSeller(Review review) {
        String fName = review.getFirstName();
        String lName = review.getLastName();
        String phone = review.getPhoneNumber();

        if (Boolean.TRUE.equals(review.getIsAnonymous())) {
            fName = mask(fName);
            lName = mask(lName);
            phone = maskPhone(phone);
        }

        return new ReviewResponse(
                review.getId(),
                review.getProductId(),
                review.getCustomerId(),
                fName,
                lName,
                phone,
                review.getRating(),
                review.getTitle(),
                review.getComment(),
                review.getImages(),
                review.getVideo(),
                review.getIsAnonymous(),
                review.getIsVerifiedBuyer(),
                review.getStatus(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "***";
        }
        String trimmed = value.trim();
        return trimmed.charAt(0) + "***";
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "******";
        }
        return "******" + phone.substring(phone.length() - 4);
    }
}
