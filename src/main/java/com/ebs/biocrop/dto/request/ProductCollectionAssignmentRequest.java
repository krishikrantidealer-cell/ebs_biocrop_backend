package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Collection and subcollection IDs selected for a product by an administrator. */
public record ProductCollectionAssignmentRequest(
        @NotNull @Size(max = 50) List<@NotNull @Size(max = 24) String> collectionIds,
        @NotNull @Size(max = 100) List<@NotNull @Size(max = 24) String> subCollectionIds) {
}
