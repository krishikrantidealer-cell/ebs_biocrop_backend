package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Category;

import java.time.Instant;

public record CategoryResponse(String id, String name, String slug, String parentId, Integer level,
                              Integer sortOrder, Boolean isActive, Instant createdAt, Instant updatedAt) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug(),
                category.getParentId(), category.getLevel(), category.getSortOrder(),
                category.getIsActive(), category.getCreatedAt(), category.getUpdatedAt());
    }
}
