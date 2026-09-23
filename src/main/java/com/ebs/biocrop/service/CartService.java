package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.request.CartItemRequest;
import com.ebs.biocrop.dto.request.CartSyncRequest;
import com.ebs.biocrop.dto.response.CartCountResponse;
import com.ebs.biocrop.dto.response.CartResponse;
import com.ebs.biocrop.dto.response.CheckoutSummaryResponse;

public interface CartService {

    CartResponse getCart(String phoneNumber);

    CartCountResponse getCartCount(String phoneNumber);

    CartResponse addToCart(String phoneNumber, CartItemRequest request);

    CartResponse updateQuantity(String phoneNumber, String variationCode, Integer quantity);

    CartResponse removeItem(String phoneNumber, String variationCode);

    CartResponse clearCart(String phoneNumber);

    CartResponse syncCart(String phoneNumber, CartSyncRequest request);

    CheckoutSummaryResponse getCheckoutSummary(String phoneNumber);
}
