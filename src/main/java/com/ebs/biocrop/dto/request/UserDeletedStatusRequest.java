package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UserDeletedStatusRequest(
        @Size(max = 100, message = "At most 100 users can be updated at once")
        List<String> userIds,
        @NotNull(message = "deleted flag is required") Boolean deleted
) {
    public UserDeletedStatusRequest(Boolean deleted) {
        this(null, deleted);
    }
}
