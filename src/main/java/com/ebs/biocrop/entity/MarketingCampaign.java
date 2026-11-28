package com.ebs.biocrop.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Marketing & engagement campaign persisted in `marketing_campaigns`. */
@Document(collection = "marketing_campaigns")
public class MarketingCampaign {

    @Id
    private String id;
    private String title;
    private String channel; // PUSH, WHATSAPP, EMAIL, BANNER, PROMO
    private String targetAudience; // ALL_FARMERS, REPEAT_BUYERS, NEW_SIGNUPS, CART_ABANDONED
    private String message;
    private String ctaUrl;
    private String status = "ACTIVE"; // DRAFT, ACTIVE, SENT, PAUSED
    private long sentCount = 0;
    private long deliveredCount = 0;
    private long openedCount = 0;
    private long clickedCount = 0;
    private long convertedCount = 0;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    public MarketingCampaign() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getTargetAudience() { return targetAudience; }
    public void setTargetAudience(String targetAudience) { this.targetAudience = targetAudience; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getCtaUrl() { return ctaUrl; }
    public void setCtaUrl(String ctaUrl) { this.ctaUrl = ctaUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getSentCount() { return sentCount; }
    public void setSentCount(long sentCount) { this.sentCount = sentCount; }
    public long getDeliveredCount() { return deliveredCount; }
    public void setDeliveredCount(long deliveredCount) { this.deliveredCount = deliveredCount; }
    public long getOpenedCount() { return openedCount; }
    public void setOpenedCount(long openedCount) { this.openedCount = openedCount; }
    public long getClickedCount() { return clickedCount; }
    public void setClickedCount(long clickedCount) { this.clickedCount = clickedCount; }
    public long getConvertedCount() { return convertedCount; }
    public void setConvertedCount(long convertedCount) { this.convertedCount = convertedCount; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
