package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotNull;

public record ProductFeaturedRequest(@NotNull Boolean featured) {
}
