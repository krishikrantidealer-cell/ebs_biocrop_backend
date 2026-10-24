package com.ebs.biocrop.dto.request;

import com.ebs.biocrop.entity.OrderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateOrderStatusRequest(
        @NotNull OrderStatus status,
        @Size(max = 500, message = "Status note must be at most 500 characters") String note) {
}
