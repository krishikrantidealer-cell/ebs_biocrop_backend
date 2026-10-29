package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.service.GcsImageStorageService;
import java.time.Instant;

/** Full persisted category projection for admin endpoints, including the storage reference. */
public record AdminCategoryResponse(String id, String name, String slug, String parentId, Integer level,
        Integer sortOrder, Boolean isActive, String bannerImage, String cataloguePdf,
        String categoryImageUri, String categoryImageUrl, String bannerTitle,
        Instant createdAt, Instant updatedAt) {
    public static AdminCategoryResponse from(Category category, GcsImageStorageService imageStorage) {
        String readUrl = category.getCategoryImageUri() == null || imageStorage == null ? null
                : imageStorage.createCategoryImageReadUrl(category.getId(), category.getCategoryImageUri());
        return new AdminCategoryResponse(category.getId(), category.getName(), category.getSlug(),
                category.getParentId(), category.getLevel(), category.getSortOrder(), category.getIsActive(),
                category.getBannerImage(), category.getCataloguePdf(), category.getCategoryImageUri(), readUrl,
                category.getBannerTitle(), category.getCreatedAt(), category.getUpdatedAt());
    }
}
