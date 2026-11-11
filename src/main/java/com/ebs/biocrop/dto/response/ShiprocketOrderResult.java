package com.ebs.biocrop.dto.response;

public record ShiprocketOrderResult(
        Long shiprocketOrderId,
        Long shipmentId,
        String awbCode,
        String courierName,
        String courierId,
        String trackingUrl,
        String status) {
}
