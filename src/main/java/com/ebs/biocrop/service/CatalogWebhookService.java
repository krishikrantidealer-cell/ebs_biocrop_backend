package com.ebs.biocrop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dispatches non-blocking HTTP webhooks to configured storefront/CDN revalidation
 * endpoints whenever merchandising collections are created, updated, deactivated, or deleted.
 */
@Service
public class CatalogWebhookService {
    private static final Logger log = LoggerFactory.getLogger(CatalogWebhookService.class);

    private final ObjectMapper objectMapper;
    private final List<String> collectionWebhookUrls;
    private final String webhookSecret;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    public CatalogWebhookService(
            ObjectMapper objectMapper,
            @Value("${app.webhooks.collection-urls:}") String collectionUrls,
            @Value("${app.webhooks.secret:}") String webhookSecret) {
        this.objectMapper = objectMapper;
        this.collectionWebhookUrls = collectionUrls == null || collectionUrls.isBlank()
                ? List.of()
                : Arrays.stream(collectionUrls.split(","))
                        .map(String::trim)
                        .filter(url -> !url.isBlank())
                        .toList();
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "catalog-webhook-dispatcher");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void publishCollectionEvent(String eventName, Map<String, Object> data) {
        if (collectionWebhookUrls.isEmpty() || eventName == null || eventName.isBlank()) {
            return;
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", eventName);
        envelope.put("timestamp", Instant.now().toString());
        envelope.put("data", data == null ? Map.of() : data);

        final String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("[Webhook] Failed to serialize payload for {}: {}", eventName, e.getMessage());
            return;
        }

        String signature = sign(body);
        for (String targetUrl : collectionWebhookUrls) {
            executor.submit(() -> deliver(targetUrl, eventName, body, signature));
        }
    }

    private void deliver(String targetUrl, String eventName, String body, String signature) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Event", eventName)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (signature != null) {
                builder.header("X-Webhook-Signature", "sha256=" + signature);
            }
            HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 400) {
                log.warn("[Webhook] {} returned HTTP {} for event {}", targetUrl, response.statusCode(), eventName);
            }
        } catch (Exception e) {
            log.warn("[Webhook] Delivery failed for {} ({}): {}", targetUrl, eventName, e.getMessage());
        }
    }

    private String sign(String payload) {
        if (webhookSecret.isEmpty()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("[Webhook] Failed to compute HMAC signature: {}", e.getMessage());
            return null;
        }
    }
}
