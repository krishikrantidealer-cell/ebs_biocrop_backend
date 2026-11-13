package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.response.RazorpayOrderResult;

import java.util.Map;

/**
 * Service for managing Razorpay payment gateway order creation, verification, and webhooks.
 */
public interface RazorpayService {

    record PaymentDetails(String paymentId, String orderId, long amountPaise, String currency,
                          String status, boolean captured) { }
    record OrderDetails(String orderId, long amountPaise, String currency, String status, Map<String, String> notes) { }

    /**
     * Creates an order with Razorpay in INR.
     */
    RazorpayOrderResult createOrder(double amountInRupees, String receipt, String currency, Map<String, String> notes);

    /**
     * Cryptographically verifies the payment signature returned by the client checkout.
     */
    boolean verifyPaymentSignature(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature);

    /** Fetches a payment from Razorpay's server API; client-supplied status/amount is never trusted. */
    PaymentDetails fetchPaymentDetails(String razorpayPaymentId);

    /** Fetches the provider order and its server-stored notes to bind payment to the initiating customer. */
    OrderDetails fetchOrderDetails(String razorpayOrderId);

    /**
     * Verifies the authenticity of Razorpay webhooks.
     */
    boolean verifyWebhookSignature(String requestBody, String webhookSignature);

    /**
     * Returns the configured public Razorpay Key ID.
     */
    String getKeyId();

    /**
     * Checks if Razorpay credentials are fully configured.
     */
    boolean isConfigured();
}
