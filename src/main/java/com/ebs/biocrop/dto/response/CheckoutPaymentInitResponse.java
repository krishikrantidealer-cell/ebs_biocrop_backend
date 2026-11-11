package com.ebs.biocrop.dto.response;

public record CheckoutPaymentInitResponse(
        String razorpayOrderId,
        String keyId,
        long amountPaise,
        double amountInRupees,
        String currency,
        double totalCartAmount,
        double advancePercentage,
        String prefillName,
        String prefillContact,
        String prefillEmail) {
}
