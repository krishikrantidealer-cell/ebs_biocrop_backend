package com.ebs.biocrop.service;

import java.util.List;
import java.util.Map;

/**
 * Handles Shiprocket Fastrr 1-Click Checkout token generation, HMAC-SHA256
 * webhook verification, and idempotent order creation.
 */
public interface FastrrCheckoutService {

    boolean isConfigured();

    String getApiKey();

    String getStoreUrl();

    boolean verifyWebhookSignature(String rawBody, String signatureHeader);

    Map<String, Object> createCheckoutToken(List<Map<String, Object>> fastrrItems, String redirectUrl);

    Map<String, Object> processOrderWebhook(Map<String, Object> payload);

    default void pushProductWebhookAsync(Map<String, Object> productPayload) {}

    default void pushCollectionWebhookAsync(Map<String, Object> collectionPayload) {}
}
