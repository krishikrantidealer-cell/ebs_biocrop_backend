package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.response.RazorpayOrderResult;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.service.RazorpayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class RazorpayServiceImpl implements RazorpayService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayServiceImpl.class);
    private static final String RAZORPAY_API_URL = "https://api.razorpay.com/v1/orders";

    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${app.razorpay.enabled:true}")
    private boolean enabled = true;

    @Value("${app.razorpay.key-id:rzp_test_ebs_biocrop_default}")
    private String keyId = "rzp_test_ebs_biocrop_default";

    @Value("${app.razorpay.key-secret:ebs_biocrop_secret_default_2026}")
    private String keySecret = "ebs_biocrop_secret_default_2026";

    @Value("${app.razorpay.webhook-secret:ebs_biocrop_webhook_secret_2026}")
    private String webhookSecret = "ebs_biocrop_webhook_secret_2026";

    public RazorpayServiceImpl(Environment environment, ObjectMapper objectMapper) {
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public boolean isConfigured() {
        return enabled && keyId != null && !keyId.isBlank()
                && keySecret != null && !keySecret.isBlank()
                && !keyId.contains("placeholder");
    }

    @Override
    public String getKeyId() {
        return keyId;
    }

    @Override
    public RazorpayOrderResult createOrder(double amountInRupees, String receipt, String currency, Map<String, String> notes) {
        long amountPaise = BigDecimal.valueOf(amountInRupees)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();

        String resolvedCurrency = (currency != null && !currency.isBlank()) ? currency.toUpperCase() : "INR";

        // Dev mock fallback if credentials are placeholders or mock
        boolean isMockCredentials = keyId.startsWith("rzp_test_ebs_biocrop") || !isConfigured();
        if (isMockCredentials && environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            String mockOrderId = "order_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            log.info("[RAZORPAY-DEV-MOCK] Generated mock order [{}] for ₹{} ({} paise)", mockOrderId, amountInRupees, amountPaise);
            return new RazorpayOrderResult(
                    mockOrderId,
                    keyId,
                    amountPaise,
                    amountInRupees,
                    resolvedCurrency,
                    receipt,
                    "created",
                    notes
            );
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("amount", amountPaise);
            payload.put("currency", resolvedCurrency);
            if (receipt != null && !receipt.isBlank()) {
                payload.put("receipt", receipt);
            }
            if (notes != null && !notes.isEmpty()) {
                payload.put("notes", notes);
            }

            String json = objectMapper.writeValueAsString(payload);
            String credentials = keyId + ":" + keySecret;
            String encodedAuth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RAZORPAY_API_URL))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Basic " + encodedAuth)
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            log.info("Creating Razorpay order for amount: ₹{} ({} paise), receipt: {}", amountInRupees, amountPaise, receipt);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(response.body());
                String orderId = root.path("id").asText();
                String status = root.path("status").asText();
                log.info("Razorpay order successfully created: [{}] status: [{}]", orderId, status);
                return new RazorpayOrderResult(
                        orderId,
                        keyId,
                        amountPaise,
                        amountInRupees,
                        resolvedCurrency,
                        receipt,
                        status,
                        notes
                );
            } else {
                log.error("Razorpay API returned error status [{}] response: {}", response.statusCode(), response.body());
                if (environment.acceptsProfiles(Profiles.of("dev"))) {
                    String mockOrderId = "order_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
                    log.warn("[DEV-FALLBACK] Razorpay failed with status {}, fallback to mock order [{}]", response.statusCode(), mockOrderId);
                    return new RazorpayOrderResult(mockOrderId, keyId, amountPaise, amountInRupees, resolvedCurrency, receipt, "created", notes);
                }
                throw new AppException("Failed to initialize payment gateway order: " + response.body(), HttpStatus.BAD_GATEWAY);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Exception connecting to Razorpay API: {}", e.getMessage(), e);
            if (environment.acceptsProfiles(Profiles.of("dev"))) {
                String mockOrderId = "order_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
                return new RazorpayOrderResult(mockOrderId, keyId, amountPaise, amountInRupees, resolvedCurrency, receipt, "created", notes);
            }
            throw new AppException("Payment service is temporarily unavailable. Please retry shortly.", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public boolean verifyPaymentSignature(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {
        if (razorpayOrderId == null || razorpayPaymentId == null || razorpaySignature == null) {
            return false;
        }

        // In dev mock testing:
        if (razorpayOrderId.startsWith("order_mock_") && environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            log.info("[RAZORPAY-DEV-MOCK] Bypassing HMAC verification for mock order [{}]", razorpayOrderId);
            return true;
        }

        try {
            String data = razorpayOrderId + "|" + razorpayPaymentId;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            String generatedSignature = HexFormat.of().formatHex(hmacBytes);

            return MessageDigest.isEqual(
                    generatedSignature.toLowerCase().getBytes(StandardCharsets.UTF_8),
                    razorpaySignature.toLowerCase().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Error computing Razorpay HMAC-SHA256 signature: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean verifyWebhookSignature(String requestBody, String webhookSignature) {
        if (requestBody == null || webhookSignature == null || webhookSecret == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hmacBytes = mac.doFinal(requestBody.getBytes(StandardCharsets.UTF_8));
            String generated = HexFormat.of().formatHex(hmacBytes);

            return MessageDigest.isEqual(
                    generated.toLowerCase().getBytes(StandardCharsets.UTF_8),
                    webhookSignature.toLowerCase().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Error verifying Razorpay webhook signature: {}", e.getMessage());
            return false;
        }
    }
}
