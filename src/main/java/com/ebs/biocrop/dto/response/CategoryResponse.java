package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.service.GcsImageStorageService;

import java.time.Instant;

public record CategoryResponse(String id, String name, String slug, String parentId, Integer level,
                              Integer sortOrder, Boolean isActive, String bannerImage, String cataloguePdf,
                              String iconImage, String iconUrl, String bannerTitle,
                              Instant createdAt, Instant updatedAt) {
    public static CategoryResponse from(Category category) {
        return from(category, null);
    }

    public static CategoryResponse from(Category category, GcsImageStorageService imageStorage) {
        String iconImage = category.getIconImage();
        String objectPrefix = "Categories/" + category.getId() + "/icons/";
        if (imageStorage != null && iconImage != null && iconImage.startsWith(objectPrefix)) {
            iconImage = imageStorage.createCategoryIconReadUrl(category.getId(), iconImage);
        }
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug(),
                category.getParentId(), category.getLevel(), category.getSortOrder(),
                category.getIsActive(), category.getBannerImage(), category.getCataloguePdf(),
                iconImage, category.getIconUrl(), category.getBannerTitle(),
                category.getCreatedAt(), category.getUpdatedAt());
    }
}
