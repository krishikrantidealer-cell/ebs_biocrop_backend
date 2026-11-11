package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.response.RazorpayOrderResult;

import java.util.Map;

/**
 * Service for managing Razorpay payment gateway order creation, verification, and webhooks.
 */
public interface RazorpayService {

    /**
     * Creates an order with Razorpay in INR.
     */
    RazorpayOrderResult createOrder(double amountInRupees, String receipt, String currency, Map<String, String> notes);

    /**
     * Cryptographically verifies the payment signature returned by the client checkout.
     */
    boolean verifyPaymentSignature(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature);

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
