package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.entity.MarketingCampaign;
import com.ebs.biocrop.entity.MarketingWebhookEvent;
import com.ebs.biocrop.websocket.WebSocketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/marketing")
@Tag(name = "Marketing Webhooks", description = "Inbound engagement & campaign delivery webhooks synced live over WebSocket")
public class MarketingWebhookController {

    private final MongoTemplate mongoTemplate;
    private final WebSocketService webSocketService;

    @Value("${app.marketing.webhook-token:${MARKETING_WEBHOOK_TOKEN:}}")
    private String configuredWebhookToken;

    public MarketingWebhookController(MongoTemplate mongoTemplate, WebSocketService webSocketService) {
        this.mongoTemplate = mongoTemplate;
        this.webSocketService = webSocketService;
    }

    @PostMapping
    @Operation(summary = "Receive inbound marketing/engagement webhook events (DELIVERED, OPENED, CLICKED, CONVERTED)")
    public ResponseEntity<ApiResponse<MarketingWebhookEvent>> receiveWebhook(
            @RequestHeader(name = "X-Marketing-Webhook-Token", required = false) String tokenHeader,
            @RequestBody Map<String, Object> payload) {
        if (configuredWebhookToken != null && !configuredWebhookToken.isBlank()) {
            if (tokenHeader == null || !configuredWebhookToken.equals(tokenHeader.trim())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error("Invalid marketing webhook token"));
            }
        }

        String eventType = stringOrDefault(payload.get("eventType"), "DELIVERED").toUpperCase(Locale.ROOT);
        String campaignId = stringOrDefault(payload.get("campaignId"), "");
        String channel = stringOrDefault(payload.get("channel"), "PUSH").toUpperCase(Locale.ROOT);
        String recipient = stringOrDefault(payload.get("recipient"), "");
        String source = stringOrDefault(payload.get("source"), "webhook");

        MarketingWebhookEvent event = new MarketingWebhookEvent();
        event.setEventType(eventType);
        event.setCampaignId(campaignId);
        event.setChannel(channel);
        event.setRecipient(recipient);
        event.setSource(source);
        event.setPayload(payload);
        event.setReceivedAt(Instant.now());
        MarketingWebhookEvent savedEvent = mongoTemplate.save(event);

        if (!campaignId.isBlank()) {
            MarketingCampaign campaign = mongoTemplate.findById(campaignId, MarketingCampaign.class);
            if (campaign != null) {
                switch (eventType) {
                    case "DELIVERED" -> campaign.setDeliveredCount(campaign.getDeliveredCount() + 1);
                    case "OPENED" -> campaign.setOpenedCount(campaign.getOpenedCount() + 1);
                    case "CLICKED" -> campaign.setClickedCount(campaign.getClickedCount() + 1);
                    case "CONVERTED" -> campaign.setConvertedCount(campaign.getConvertedCount() + 1);
                    default -> {}
                }
                campaign.setUpdatedAt(Instant.now());
                MarketingCampaign updatedCampaign = mongoTemplate.save(campaign);
                Map<String, Object> campaignMsg = Map.of(
                        "action", "metrics_updated",
                        "campaign", updatedCampaign
                );
                webSocketService.sendToRole("ROLE_ADMIN", "marketing:campaign_updated", campaignMsg);
                webSocketService.sendToRole("ROLE_MARKETING", "marketing:campaign_updated", campaignMsg);
            }
        }

        webSocketService.sendToRole("ROLE_ADMIN", "engagement:webhook_received", savedEvent);
        webSocketService.sendToRole("ROLE_MARKETING", "engagement:webhook_received", savedEvent);

        return ResponseEntity.ok(ApiResponse.ok("Marketing webhook processed", savedEvent));
    }

    private String stringOrDefault(Object value, String fallback) {
        if (value == null) return fallback;
        String s = value.toString().trim();
        return s.isEmpty() ? fallback : s;
    }
}
