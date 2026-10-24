package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Category IDs for an explicitly requested permanent bulk deletion. */
public record DeleteCategoriesRequest(
        @NotEmpty(message = "At least one category ID is required")
        @Size(max = 100, message = "At most 100 categories can be deleted at once")
        List<@NotBlank(message = "Category IDs cannot be blank") @Size(max = 64) String> categoryIds) {
}
