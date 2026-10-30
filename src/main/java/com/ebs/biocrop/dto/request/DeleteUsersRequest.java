package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record DeleteUsersRequest(
        @Size(max = 100, message = "At most 100 users can be permanently deleted at once")
        List<String> userIds,
        @NotNull(message = "confirm must be true for permanent deletion")
        @AssertTrue(message = "confirm must be true for permanent deletion")
        Boolean confirm
) {
}
