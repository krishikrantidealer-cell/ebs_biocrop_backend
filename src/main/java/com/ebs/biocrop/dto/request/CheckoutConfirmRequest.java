package com.ebs.biocrop.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public class CheckoutConfirmRequest {

    @NotNull(message = "Order details are required")
    @Valid
    private PlaceOrderRequest orderRequest;

    private String razorpayOrderId;
    private String razorpayPaymentId;
    private String razorpaySignature;

    public PlaceOrderRequest getOrderRequest() { return orderRequest; }
    public void setOrderRequest(PlaceOrderRequest orderRequest) { this.orderRequest = orderRequest; }
    public String getRazorpayOrderId() { return razorpayOrderId; }
    public void setRazorpayOrderId(String razorpayOrderId) { this.razorpayOrderId = razorpayOrderId; }
    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String razorpayPaymentId) { this.razorpayPaymentId = razorpayPaymentId; }
    public String getRazorpaySignature() { return razorpaySignature; }
    public void setRazorpaySignature(String razorpaySignature) { this.razorpaySignature = razorpaySignature; }
}
