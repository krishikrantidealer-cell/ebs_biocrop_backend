package com.ebs.biocrop.dto.response;

import java.util.List;

public record ReviewMediaUploadResponse(
        List<String> images,
        String video
) {
    public static ReviewMediaUploadResponse of(List<String> images, String video) {
        return new ReviewMediaUploadResponse(images == null ? List.of() : List.copyOf(images), video);
    }
}
