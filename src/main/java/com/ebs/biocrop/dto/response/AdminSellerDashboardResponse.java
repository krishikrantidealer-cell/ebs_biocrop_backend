package com.ebs.biocrop.dto.response;

/** Admin-only seller overview with operational counts; contains no payout data. */
public record AdminSellerDashboardResponse(
        AdminUserResponse seller,
        long totalProducts,
        long activeProducts,
        long totalOrders,
        long totalReviews) {
}
