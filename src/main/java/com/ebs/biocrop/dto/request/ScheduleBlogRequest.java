package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public class ScheduleBlogRequest {
    @NotNull
    private Instant scheduledAt;

    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
}
