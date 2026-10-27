package com.ebs.biocrop.dto.request;

import com.ebs.biocrop.entity.enums.ReviewStatus;
import jakarta.validation.constraints.NotNull;

public class ReviewStatusUpdateRequest {

    @NotNull(message = "Review status is required")
    private ReviewStatus status;

    public ReviewStatusUpdateRequest() { }

    public ReviewStatusUpdateRequest(ReviewStatus status) {
        this.status = status;
    }

    public ReviewStatus getStatus() { return status; }
    public void setStatus(ReviewStatus status) { this.status = status; }
}
