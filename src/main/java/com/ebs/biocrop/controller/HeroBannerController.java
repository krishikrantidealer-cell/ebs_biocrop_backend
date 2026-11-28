package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.entity.HeroBanner;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.HeroBannerRepository;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.websocket.WebSocketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@Tag(name = "Hero Banners", description = "Public and Admin APIs for dynamic home screen carousel banners")
public class HeroBannerController {

    private final HeroBannerRepository bannerRepository;
    private final GcsImageStorageService imageStorage;
    private final WebSocketService webSocketService;

    public HeroBannerController(
            HeroBannerRepository bannerRepository,
            GcsImageStorageService imageStorage,
            WebSocketService webSocketService) {
        this.bannerRepository = bannerRepository;
        this.imageStorage = imageStorage;
        this.webSocketService = webSocketService;
    }

    public record BannerWriteRequest(
            String title,
            String subtitle,
            String tag,
            String buttonText,
            String ctaLink,
            Integer sortOrder,
            Boolean active
    ) {}

    public record BannerReorderRequest(List<String> orderedIds) {}

    @GetMapping("/api/v1/banners")
    @Operation(summary = "List active hero banners for the storefront")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPublicBanners() {
        List<Map<String, Object>> banners = bannerRepository.findAll().stream()
                .filter(b -> Boolean.TRUE.equals(b.getActive()))
                .sorted(Comparator
                        .comparing(HeroBanner::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(HeroBanner::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok("Active hero banners", banners));
    }

    @GetMapping("/api/v1/admin/banners")
    @PreAuthorize("@permissionGuard.hasAny(authentication, 'CATALOG_VIEW', 'CATALOG_MANAGE')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List all hero banners for Admin / Catalog Managers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAdminBanners() {
        List<Map<String, Object>> banners = bannerRepository.findAll().stream()
                .sorted(Comparator
                        .comparing(HeroBanner::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(HeroBanner::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok("Admin hero banners", banners));
    }

    @PostMapping("/api/v1/admin/banners")
    @PreAuthorize("@permissionGuard.has(authentication, 'CATALOG_MANAGE')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a hero banner")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createBanner(
            @AuthenticationPrincipal CustomUserDetails admin,
            @RequestBody BannerWriteRequest req) {
        HeroBanner banner = new HeroBanner();
        applyFields(banner, req);
        banner.setCreatedAt(LocalDateTime.now());
        banner.setUpdatedAt(LocalDateTime.now());
        HeroBanner saved = bannerRepository.save(banner);
        broadcastBannerChange("CREATED", saved.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Hero banner created", toDto(saved)));
    }

    @PutMapping("/api/v1/admin/banners/reorder")
    @PreAuthorize("@permissionGuard.has(authentication, 'CATALOG_MANAGE')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Atomically reorder hero banners and broadcast live update")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> reorderBanners(
            @RequestBody BannerReorderRequest req) {
        if (req == null || req.orderedIds() == null || req.orderedIds().isEmpty()) {
            throw new AppException("orderedIds is required", HttpStatus.BAD_REQUEST);
        }
        List<HeroBanner> all = bannerRepository.findAll();
        Map<String, HeroBanner> byId = new LinkedHashMap<>();
        for (HeroBanner b : all) {
            byId.put(b.getId(), b);
        }

        LocalDateTime now = LocalDateTime.now();
        List<HeroBanner> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int pos = 1;
        for (String id : req.orderedIds()) {
            if (id == null || !seen.add(id)) continue;
            HeroBanner b = byId.get(id);
            if (b != null) {
                b.setSortOrder(pos++);
                b.setUpdatedAt(now);
                ordered.add(b);
            }
        }
        all.stream()
                .filter(b -> !seen.contains(b.getId()))
                .sorted(Comparator
                        .comparing(HeroBanner::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(HeroBanner::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(b -> {
                    b.setSortOrder(ordered.size() + 1);
                    b.setUpdatedAt(now);
                    ordered.add(b);
                });

        List<HeroBanner> saved = bannerRepository.saveAll(ordered);
        saved.sort(Comparator.comparing(HeroBanner::getSortOrder, Comparator.nullsLast(Integer::compareTo)));
        broadcastBannerChange("REORDERED", "bulk");
        return ResponseEntity.ok(ApiResponse.ok("Hero banners reordered", saved.stream().map(this::toDto).toList()));
    }

    @PutMapping("/api/v1/admin/banners/{bannerId}")
    @PreAuthorize("@permissionGuard.has(authentication, 'CATALOG_MANAGE')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update a hero banner")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateBanner(
            @PathVariable String bannerId,
            @RequestBody BannerWriteRequest req) {
        HeroBanner banner = bannerRepository.findById(bannerId)
                .orElseThrow(() -> new ResourceNotFoundException("HeroBanner", "id", bannerId));
        applyFields(banner, req);
        banner.setUpdatedAt(LocalDateTime.now());
        HeroBanner saved = bannerRepository.save(banner);
        broadcastBannerChange("UPDATED", saved.getId());
        return ResponseEntity.ok(ApiResponse.ok("Hero banner updated", toDto(saved)));
    }

    @PostMapping(value = "/api/v1/admin/banners/{bannerId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@permissionGuard.has(authentication, 'CATALOG_MANAGE')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Upload/replace hero banner image")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadBannerImage(
            @PathVariable String bannerId,
            @RequestPart("file") MultipartFile file) {
        HeroBanner banner = bannerRepository.findById(bannerId)
                .orElseThrow(() -> new ResourceNotFoundException("HeroBanner", "id", bannerId));
        if (file == null || file.isEmpty()) {
            throw new AppException("Banner image file is required", HttpStatus.BAD_REQUEST);
        }
        String contentType = file.getContentType() == null ? "image/jpeg" : file.getContentType().toLowerCase(Locale.ROOT);
        String ext = switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new AppException("Failed to read banner image bytes", HttpStatus.BAD_REQUEST);
        }
        String oldUri = banner.getImageUri();
        String newUri = imageStorage.uploadBannerImage(bannerId, bytes, ext, contentType);
        banner.setImageUri(newUri);
        banner.setUpdatedAt(LocalDateTime.now());
        HeroBanner saved = bannerRepository.save(banner);
        if (oldUri != null && !oldUri.equals(newUri)) {
            imageStorage.deleteBannerImage(bannerId, oldUri);
        }
        broadcastBannerChange("IMAGE_UPDATED", saved.getId());
        return ResponseEntity.ok(ApiResponse.ok("Hero banner image uploaded", toDto(saved)));
    }

    @PatchMapping("/api/v1/admin/banners/{bannerId}/toggle")
    @PreAuthorize("@permissionGuard.has(authentication, 'CATALOG_MANAGE')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Toggle hero banner active status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> toggleBanner(@PathVariable String bannerId) {
        HeroBanner banner = bannerRepository.findById(bannerId)
                .orElseThrow(() -> new ResourceNotFoundException("HeroBanner", "id", bannerId));
        banner.setActive(!Boolean.TRUE.equals(banner.getActive()));
        banner.setUpdatedAt(LocalDateTime.now());
        HeroBanner saved = bannerRepository.save(banner);
        broadcastBannerChange("TOGGLED", saved.getId());
        return ResponseEntity.ok(ApiResponse.ok("Hero banner toggled", toDto(saved)));
    }

    @DeleteMapping("/api/v1/admin/banners/{bannerId}")
    @PreAuthorize("@permissionGuard.has(authentication, 'CATALOG_MANAGE')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete a hero banner")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteBanner(@PathVariable String bannerId) {
        HeroBanner banner = bannerRepository.findById(bannerId)
                .orElseThrow(() -> new ResourceNotFoundException("HeroBanner", "id", bannerId));
        imageStorage.deleteBannerImage(bannerId, banner.getImageUri());
        bannerRepository.delete(banner);
        broadcastBannerChange("DELETED", bannerId);
        return ResponseEntity.ok(ApiResponse.ok("Hero banner deleted", Map.of("id", bannerId)));
    }

    private void applyFields(HeroBanner banner, BannerWriteRequest req) {
        if (req.title() != null) banner.setTitle(req.title().trim());
        if (req.subtitle() != null) banner.setSubtitle(req.subtitle().trim());
        if (req.tag() != null) banner.setTag(req.tag().trim());
        if (req.buttonText() != null) banner.setButtonText(req.buttonText().trim());
        if (req.ctaLink() != null) banner.setCtaLink(req.ctaLink().trim());
        if (req.sortOrder() != null) banner.setSortOrder(req.sortOrder());
        if (req.active() != null) banner.setActive(req.active());
    }

    private void broadcastBannerChange(String action, String bannerId) {
        webSocketService.broadcast("banners:updated", Map.of(
                "action", action,
                "bannerId", bannerId,
                "timestamp", Instant.now().toString()
        ));
    }

    private Map<String, Object> toDto(HeroBanner b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("title", b.getTitle() == null ? "" : b.getTitle());
        m.put("subtitle", b.getSubtitle() == null ? "" : b.getSubtitle());
        m.put("tag", b.getTag() == null ? "" : b.getTag());
        m.put("buttonText", b.getButtonText() == null ? "" : b.getButtonText());
        m.put("ctaLink", b.getCtaLink() == null ? "" : b.getCtaLink());
        m.put("imageUrl", imageStorage.createBannerImageReadUrl(b.getId(), b.getImageUri()));
        m.put("sortOrder", b.getSortOrder() == null ? 0 : b.getSortOrder());
        m.put("active", Boolean.TRUE.equals(b.getActive()));
        m.put("createdAt", b.getCreatedAt() != null ? b.getCreatedAt().toString() : null);
        m.put("updatedAt", b.getUpdatedAt() != null ? b.getUpdatedAt().toString() : null);
        return m;
    }
}
