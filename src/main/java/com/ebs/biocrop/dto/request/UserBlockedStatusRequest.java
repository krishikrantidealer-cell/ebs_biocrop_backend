package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotNull;

public record UserBlockedStatusRequest(@NotNull Boolean blocked) {
}
