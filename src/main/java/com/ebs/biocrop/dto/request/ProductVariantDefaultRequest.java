package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotNull;

public record ProductVariantDefaultRequest(
        @NotNull(message = "isDefault flag is required") Boolean isDefault) {
}
