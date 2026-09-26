package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.response.WishlistResponse;

public interface WishlistService {
    WishlistResponse getWishlist(String userId);
    WishlistResponse addVariant(String userId, String variantId);
    WishlistResponse removeVariant(String userId, String variantId);
    WishlistResponse clearWishlist(String userId);
}
