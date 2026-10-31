package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Collection IDs for an explicitly requested permanent deletion (single or bulk). */
public record DeleteCollectionsRequest(
        @NotEmpty(message = "At least one collection ID is required")
        @Size(max = 100, message = "At most 100 collections can be deleted at once")
        List<@NotBlank(message = "Collection IDs cannot be blank") @Size(max = 64) String> collectionIds) {
}
