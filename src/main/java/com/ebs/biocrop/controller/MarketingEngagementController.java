package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.entity.Blog;
import com.ebs.biocrop.entity.HeroBanner;
import com.ebs.biocrop.entity.MarketingCampaign;
import com.ebs.biocrop.entity.MarketingWebhookEvent;
import com.ebs.biocrop.entity.ProductCollection;
import com.ebs.biocrop.entity.Review;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.websocket.WebSocketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/marketing")
@Tag(name = "Marketing & Engagement", description = "Campaigns, merchandising KPIs, and engagement telemetry")
@SecurityRequirement(name = "bearerAuth")
public class MarketingEngagementController {

    private final MongoTemplate mongoTemplate;
    private final WebSocketService webSocketService;

    public MarketingEngagementController(MongoTemplate mongoTemplate, WebSocketService webSocketService) {
        this.mongoTemplate = mongoTemplate;
        this.webSocketService = webSocketService;
    }

    @GetMapping("/overview")
    @PreAuthorize("@permissionGuard.hasAny(authentication, 'MARKETING_VIEW', 'MARKETING_MANAGE', 'ENGAGEMENT_VIEW', 'ENGAGEMENT_MANAGE')")
    @Operation(summary = "Get marketing & engagement overview metrics, campaigns, and recent webhook events")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOverview() {
        long totalBlogs = mongoTemplate.count(new Query(), Blog.class);
        long publishedBlogs = mongoTemplate.count(Query.query(Criteria.where("status").is("PUBLISHED")), Blog.class);
        long activeBanners = mongoTemplate.count(Query.query(Criteria.where("isActive").is(true)), HeroBanner.class);
        long activeCollections = mongoTemplate.count(Query.query(Criteria.where("isActive").is(true)), ProductCollection.class);
        long totalReviews = mongoTemplate.count(new Query(), Review.class);
        long totalCustomers = mongoTemplate.count(
                Query.query(Criteria.where("role").is(UserRole.ROLE_CUSTOMER.name()).and("isDeleted").ne(true)),
                User.class);

        List<MarketingCampaign> campaigns = mongoTemplate.find(
                new Query().with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(30),
                MarketingCampaign.class);

        List<MarketingWebhookEvent> recentWebhooks = mongoTemplate.find(
                new Query().with(Sort.by(Sort.Direction.DESC, "receivedAt")).limit(25),
                MarketingWebhookEvent.class);

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("totalBlogs", totalBlogs);
        kpis.put("publishedBlogs", publishedBlogs);
        kpis.put("activeBanners", activeBanners);
        kpis.put("activeCollections", activeCollections);
        kpis.put("totalReviews", totalReviews);
        kpis.put("totalCustomers", totalCustomers);
        kpis.put("totalCampaigns", campaigns.size());
        kpis.put("totalWebhookEvents", mongoTemplate.count(new Query(), MarketingWebhookEvent.class));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kpis", kpis);
        data.put("campaigns", campaigns);
        data.put("recentWebhooks", recentWebhooks);
        return ResponseEntity.ok(ApiResponse.ok("Marketing & engagement overview retrieved", data));
    }

    @PostMapping("/campaigns")
    @PreAuthorize("@permissionGuard.hasAny(authentication, 'MARKETING_MANAGE', 'ENGAGEMENT_MANAGE')")
    @Operation(summary = "Create and broadcast a new marketing/engagement campaign")
    public ResponseEntity<ApiResponse<MarketingCampaign>> createCampaign(
            @Valid @RequestBody CreateCampaignRequest request,
            Authentication authentication) {
        MarketingCampaign campaign = new MarketingCampaign();
        campaign.setTitle(request.title().trim());
        campaign.setChannel(normalizeUpper(request.channel(), "PUSH"));
        campaign.setTargetAudience(normalizeUpper(request.targetAudience(), "ALL_FARMERS"));
        campaign.setMessage(request.message().trim());
        campaign.setCtaUrl(request.ctaUrl() == null ? "" : request.ctaUrl().trim());
        campaign.setStatus(normalizeUpper(request.status(), "ACTIVE"));
        campaign.setCreatedBy(authentication != null ? authentication.getName() : "admin");
        campaign.setCreatedAt(Instant.now());
        campaign.setUpdatedAt(Instant.now());

        if ("SENT".equals(campaign.getStatus()) || "ACTIVE".equals(campaign.getStatus())) {
            long customerCount = mongoTemplate.count(
                    Query.query(Criteria.where("role").is(UserRole.ROLE_CUSTOMER.name()).and("isDeleted").ne(true)),
                    User.class);
            campaign.setSentCount(customerCount);
        }

        MarketingCampaign saved = mongoTemplate.save(campaign);
        broadcastCampaignUpdate("created", saved);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Campaign created & synced", saved));
    }

    @PatchMapping("/campaigns/{id}/status")
    @PreAuthorize("@permissionGuard.hasAny(authentication, 'MARKETING_MANAGE', 'ENGAGEMENT_MANAGE')")
    @Operation(summary = "Update campaign status and broadcast live via WebSocket")
    public ResponseEntity<ApiResponse<MarketingCampaign>> updateCampaignStatus(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        MarketingCampaign campaign = mongoTemplate.findById(id, MarketingCampaign.class);
        if (campaign == null) {
            throw new ResourceNotFoundException("Campaign", "id", id);
        }
        campaign.setStatus(normalizeUpper(body.get("status"), campaign.getStatus()));
        campaign.setUpdatedAt(Instant.now());
        MarketingCampaign saved = mongoTemplate.save(campaign);
        broadcastCampaignUpdate("status_updated", saved);
        return ResponseEntity.ok(ApiResponse.ok("Campaign status updated", saved));
    }

    private void broadcastCampaignUpdate(String action, MarketingCampaign campaign) {
        Map<String, Object> payload = Map.of(
                "action", action,
                "campaign", campaign
        );
        webSocketService.sendToRole("ROLE_ADMIN", "marketing:campaign_updated", payload);
        webSocketService.sendToRole("ROLE_MARKETING", "marketing:campaign_updated", payload);
    }

    private String normalizeUpper(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    public record CreateCampaignRequest(
            @NotBlank String title,
            String channel,
            String targetAudience,
            @NotBlank String message,
            String ctaUrl,
            String status
    ) {}
}
