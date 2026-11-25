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
            @RequestHeader(name = "X-Velocity-Token", required = false) String velocityToken,
            @RequestHeader(name = "X-Webhook-Token", required = false) String webhookToken,
            @RequestHeader(name = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> payload) {
        String token = (velocityToken != null && !velocityToken.isBlank())
                ? velocityToken
                : ((webhookToken != null && !webhookToken.isBlank()) ? webhookToken : authHeader);
        try {
            Map<String, Object> result = new LinkedHashMap<>(velocityService.processTrackingWebhook(payload, token));
            result.put("timestamp", Instant.now().toString());
            return ResponseEntity.ok(result);
        } catch (AppException ex) {
            log.warn("[VELOCITY-WEBHOOK] Rejected ({}): {}", ex.getStatus().value(), ex.getMessage());
            return ResponseEntity.status(ex.getStatus()).body(Map.of(
                    "status", "ERROR",
                    "message", ex.getMessage(),
                    "timestamp", Instant.now().toString()
            ));
        }
    }
}
