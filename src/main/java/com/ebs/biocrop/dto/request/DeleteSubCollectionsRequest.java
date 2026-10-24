package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Subcollection IDs to remove from one collection. */
public record DeleteSubCollectionsRequest(
        @NotEmpty(message = "At least one subcollection ID is required")
        @Size(max = 100, message = "At most 100 subcollections can be deleted at once")
        List<@NotBlank(message = "Subcollection IDs cannot be blank") @Size(max = 24) String> subCollectionIds) {
}
