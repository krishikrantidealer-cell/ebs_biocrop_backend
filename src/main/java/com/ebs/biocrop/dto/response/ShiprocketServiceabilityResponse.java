package com.ebs.biocrop.dto.response;

public record ShiprocketServiceabilityResponse(
        String deliveryPincode,
        boolean serviceable,
        int estimatedDeliveryDays,
        String courierName,
        double shippingCharge,
        String message) {
}
