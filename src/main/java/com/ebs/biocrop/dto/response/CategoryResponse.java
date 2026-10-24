package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.service.GcsImageStorageService;

import java.time.Instant;

public record CategoryResponse(String id, String name, String slug, String parentId, Integer level,
                              Integer sortOrder, Boolean isActive, String bannerImage, String cataloguePdf,
                              String categoryImageUrl, String bannerTitle,
                              Instant createdAt, Instant updatedAt) {
    public static CategoryResponse from(Category category) {
        return from(category, null);
    }

    public static CategoryResponse from(Category category, GcsImageStorageService imageStorage) {
        String categoryImageUrl = category.getCategoryImageUri() == null || imageStorage == null ? null
                : imageStorage.createCategoryImageReadUrl(category.getId(), category.getCategoryImageUri());
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug(),
                category.getParentId(), category.getLevel(), category.getSortOrder(),
                category.getIsActive(), category.getBannerImage(), category.getCataloguePdf(),
                categoryImageUrl, category.getBannerTitle(),
                category.getCreatedAt(), category.getUpdatedAt());
    }
}
