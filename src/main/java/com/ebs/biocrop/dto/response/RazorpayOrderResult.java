package com.ebs.biocrop.dto.response;

import java.util.Map;

public record RazorpayOrderResult(
        String orderId,
        String keyId,
        long amountPaise,
        double amountRupees,
        String currency,
        String receipt,
        String status,
        Map<String, String> notes) {
}
