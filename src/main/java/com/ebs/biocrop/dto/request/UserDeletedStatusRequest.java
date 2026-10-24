package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotNull;

public record UserDeletedStatusRequest(@NotNull Boolean deleted) {
}
