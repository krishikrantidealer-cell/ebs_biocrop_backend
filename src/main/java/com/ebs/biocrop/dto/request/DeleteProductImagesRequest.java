package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record DeleteProductImagesRequest(
        @NotEmpty(message = "At least one image ID is required")
        @Size(max = 6, message = "At most 6 image IDs can be deleted at once")
        List<@NotBlank(message = "Image IDs cannot be blank") String> imageIds) {
}
