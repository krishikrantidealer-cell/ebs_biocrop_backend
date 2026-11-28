package com.ebs.biocrop.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/** Inbound marketing/engagement webhook event log persisted in `marketing_webhook_events`. */
@Document(collection = "marketing_webhook_events")
public class MarketingWebhookEvent {

    @Id
    private String id;
    private String eventType; // DELIVERED, OPENED, CLICKED, CONVERTED, BOUNCED
    private String campaignId;
    private String channel;
    private String recipient;
    private String source;
    private Map<String, Object> payload;
    private Instant receivedAt;

    public MarketingWebhookEvent() {
        this.receivedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getCampaignId() { return campaignId; }
    public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
}
