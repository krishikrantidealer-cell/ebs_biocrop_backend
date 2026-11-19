package com.ebs.biocrop.controller;

import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.service.DelhiveryOneService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Webhook receiver for Delhivery One real-time shipment tracking & RTO status updates.
 */
@RestController
@RequestMapping("/api/v1/webhooks/delhivery")
@Tag(name = "Delhivery One Webhook", description = "Real-time shipping, delivery, and RTO status webhooks from Delhivery One")
public class DelhiveryWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DelhiveryWebhookController.class);
    private final DelhiveryOneService delhiveryOneService;

    public DelhiveryWebhookController(DelhiveryOneService delhiveryOneService) {
        this.delhiveryOneService = delhiveryOneService;
    }

    @PostMapping("/tracking")
    @Operation(summary = "Delhivery One Tracking & RTO Webhook")
    public ResponseEntity<Map<String, Object>> handleTrackingWebhook(
            @RequestHeader(name = "X-Delhivery-Token", required = false) String tokenHeader,
            @RequestHeader(name = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> payload) {
        String token = (tokenHeader != null && !tokenHeader.isBlank()) ? tokenHeader : authHeader;
        try {
            Map<String, Object> result = new LinkedHashMap<>(delhiveryOneService.processTrackingWebhook(payload, token));
            result.put("timestamp", Instant.now().toString());
            return ResponseEntity.ok(result);
        } catch (AppException ex) {
            log.warn("[DELHIVERY-WEBHOOK] Rejected ({}): {}", ex.getStatus().value(), ex.getMessage());
            return ResponseEntity.status(ex.getStatus()).body(Map.of(
                    "status", "ERROR",
                    "message", ex.getMessage(),
                    "timestamp", Instant.now().toString()
            ));
        }
    }
}
