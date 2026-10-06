package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.request.PlaceOrderRequest;
import com.ebs.biocrop.dto.response.PlacedOrdersResponse;

public interface OrderPlacementService {
    PlacedOrdersResponse createOrders(String customerId, PlaceOrderRequest request);
    PlacedOrdersResponse createOrdersForAdmin(String customerId, PlaceOrderRequest request, String adminId);
}
