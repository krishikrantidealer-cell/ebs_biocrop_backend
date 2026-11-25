package com.ebs.biocrop.controller;

import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.service.VelocityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Webhook receiver for Velocity Shipping real-time AWB assignment & tracking updates.
 */
@RestController
@RequestMapping("/api/v1/webhooks/velocity")
@Tag(name = "Velocity Webhook", description = "Real-time AWB assignment and shipment tracking webhooks from Velocity Shipping")
public class VelocityWebhookController {

    private static final Logger log = LoggerFactory.getLogger(VelocityWebhookController.class);
    private final VelocityService velocityService;

    public VelocityWebhookController(VelocityService velocityService) {
        this.velocityService = velocityService;
    }

    @PostMapping("/tracking")
    @Operation(summary = "Velocity Shipping Tracking & AWB Webhook")
    public ResponseEntity<Map<String, Object>> handleTrackingWebhook(
            @RequestHeader Map<String, String> headers,
            @RequestBody(required = false) Map<String, Object> payload) {
        String token = firstNonBlank(
                headerIgnoreCase(headers, "X-Velocity-Token"),
                headerIgnoreCase(headers, "X-API-Key"),
                headerIgnoreCase(headers, "Api-Key"),
                headerIgnoreCase(headers, "X-Webhook-Token"),
                headerIgnoreCase(headers, "Authorization"));
        try {
            Map<String, Object> result = new LinkedHashMap<>(velocityService.processTrackingWebhook(payload, token));
            result.put("timestamp", Instant.now().toString());
            return ResponseEntity.ok(result);
        } catch (AppException ex) {
            log.warn("[VELOCITY-WEBHOOK] Rejected ({}): {} | headerKeys={}", ex.getStatus().value(), ex.getMessage(), headers.keySet());
            return ResponseEntity.status(ex.getStatus()).body(Map.of(
                    "status", "ERROR",
                    "message", ex.getMessage(),
                    "timestamp", Instant.now().toString()
            ));
        }
    }

    private static String headerIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isBlank()) {
                return e.getValue().trim();
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
