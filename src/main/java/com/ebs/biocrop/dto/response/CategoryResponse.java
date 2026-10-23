package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Category;

import java.time.Instant;

public record CategoryResponse(String id, String name, String slug, String parentId, Integer level,
                              Integer sortOrder, Boolean isActive, String bannerImage, String cataloguePdf,
                              String iconImage, String iconUrl, String bannerTitle,
                              Instant createdAt, Instant updatedAt) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug(),
                category.getParentId(), category.getLevel(), category.getSortOrder(),
                category.getIsActive(), category.getBannerImage(), category.getCataloguePdf(),
                category.getIconImage(), category.getIconUrl(), category.getBannerTitle(),
                category.getCreatedAt(), category.getUpdatedAt());
    }
}
