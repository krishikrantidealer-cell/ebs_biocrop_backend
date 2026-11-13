package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.request.PlaceOrderRequest;
import com.ebs.biocrop.entity.Order;
import java.math.BigDecimal;

public interface OrderPlacementService {
    Order createOrders(String customerId, PlaceOrderRequest request, String idempotencyKey);
    Order createOrdersForAdmin(String customerId, PlaceOrderRequest request, String adminId, String idempotencyKey);
    BigDecimal calculateOnlinePaymentAmount(String customerId, PlaceOrderRequest request);
}
