package com.ebs.biocrop.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public class CheckoutPaymentInitRequest {
    @NotNull(message = "Order details are required")
    @Valid
    private PlaceOrderRequest orderRequest;

    public PlaceOrderRequest getOrderRequest() { return orderRequest; }
    public void setOrderRequest(PlaceOrderRequest orderRequest) { this.orderRequest = orderRequest; }
}
