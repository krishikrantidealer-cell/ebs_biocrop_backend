package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.response.ReviewMediaUploadResponse;
import com.ebs.biocrop.exception.AppException;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class GcsReviewMediaService {

    private static final Logger log = LoggerFactory.getLogger(GcsReviewMediaService.class);

    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024; // 5 MB
    private static final long MAX_VIDEO_SIZE = 50 * 1024 * 1024; // 50 MB
    private static final int MAX_IMAGE_COUNT = 5;

    private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    private static final Map<String, String> ALLOWED_VIDEO_TYPES = Map.of(
            "video/mp4", "mp4",
            "video/webm", "webm",
            "video/quicktime", "mov"
    );

    private final Storage storage;
    private final String bucketName;
    private volatile boolean bucketChecked = false;

    public GcsReviewMediaService(
            Storage storage,
            @Value("${app.gcs.review-bucket-name:sellerhub-prod-review-media-us}") String bucketName) {
        this.storage = storage;
        this.bucketName = bucketName;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void ensureBucketExists() {
        if (bucketChecked) {
            return;
        }
        try {
            if (storage.get(bucketName) == null) {
                storage.create(BucketInfo.newBuilder(bucketName)
                        .setLocation("US")
                        .build());
                log.info("Created review media GCS bucket {} in location US", bucketName);
            }
            bucketChecked = true;
        } catch (Exception e) {
            log.warn("Could not check or create bucket {}: {}", bucketName, e.getMessage());
        }
    }

    public ReviewMediaUploadResponse uploadReviewMedia(
            String productId,
            String customerId,
            List<MultipartFile> images,
            MultipartFile video) {

        requireSafeSegment(productId, "Product ID");
        requireSafeSegment(customerId, "Customer ID");

        List<MultipartFile> validImages = images == null ? List.of()
                : images.stream().filter(file -> file != null && !file.isEmpty()).toList();
        boolean hasVideo = video != null && !video.isEmpty();

        if (validImages.isEmpty() && !hasVideo) {
            throw new AppException("At least one image or video must be provided", HttpStatus.BAD_REQUEST);
        }

        if (validImages.size() > MAX_IMAGE_COUNT) {
            throw new AppException("Maximum " + MAX_IMAGE_COUNT + " images allowed per review", HttpStatus.BAD_REQUEST);
        }

        // Validate images before uploading
        for (MultipartFile file : validImages) {
            validateImageFile(file);
        }

        // Validate video before uploading
        if (hasVideo) {
            validateVideoFile(video);
        }

        ensureBucketExists();

        List<String> uploadedObjectNames = new ArrayList<>();
        List<String> uploadedImageUrls = new ArrayList<>();
        String uploadedVideoUrl = null;

        try {
            // Upload images
            for (MultipartFile file : validImages) {
                String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
                String extension = ALLOWED_IMAGE_TYPES.get(contentType);
                String objectName = "reviews/" + productId + "/" + customerId + "/images/"
                        + UUID.randomUUID() + "." + extension;

                BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, objectName)
                        .setContentType(contentType)
                        .build();

                storage.create(blobInfo, file.getBytes(), Storage.BlobTargetOption.doesNotExist());
                uploadedObjectNames.add(objectName);
                uploadedImageUrls.add(buildPublicUrl(objectName));
            }

            // Upload video
            if (hasVideo) {
                String contentType = video.getContentType() == null ? "" : video.getContentType().toLowerCase(Locale.ROOT);
                String extension = ALLOWED_VIDEO_TYPES.get(contentType);
                String objectName = "reviews/" + productId + "/" + customerId + "/videos/"
                        + UUID.randomUUID() + "." + extension;

                BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, objectName)
                        .setContentType(contentType)
                        .build();

                storage.create(blobInfo, video.getBytes(), Storage.BlobTargetOption.doesNotExist());
                uploadedObjectNames.add(objectName);
                uploadedVideoUrl = buildPublicUrl(objectName);
            }

            return ReviewMediaUploadResponse.of(uploadedImageUrls, uploadedVideoUrl);

        } catch (IOException ioException) {
            cleanupObjects(uploadedObjectNames);
            throw new AppException("Failed to read media file: " + ioException.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (RuntimeException runtimeException) {
            cleanupObjects(uploadedObjectNames);
            throw runtimeException;
        }
    }

    public void deleteMedia(List<String> imageUrls, String videoUrl) {
        List<String> allUrls = new ArrayList<>();
        if (imageUrls != null) {
            allUrls.addAll(imageUrls);
        }
        if (videoUrl != null && !videoUrl.isBlank()) {
            allUrls.add(videoUrl);
        }

        String expectedPrefix = "https://storage.googleapis.com/" + bucketName + "/";
        for (String url : allUrls) {
            if (url != null && url.startsWith(expectedPrefix)) {
                String objectName = url.substring(expectedPrefix.length());
                try {
                    storage.delete(bucketName, objectName);
                } catch (Exception e) {
                    log.warn("Failed to delete review media object {}: {}", objectName, e.getMessage());
                }
            }
        }
    }

    private void validateImageFile(MultipartFile file) {
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new AppException("Review image size cannot exceed 5MB (" + file.getOriginalFilename() + ")", HttpStatus.BAD_REQUEST);
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_IMAGE_TYPES.containsKey(contentType)) {
            throw new AppException("Only JPEG, PNG, and WEBP images are supported (" + file.getOriginalFilename() + ")", HttpStatus.BAD_REQUEST);
        }
    }

    private void validateVideoFile(MultipartFile file) {
        if (file.getSize() > MAX_VIDEO_SIZE) {
            throw new AppException("Review video size cannot exceed 50MB", HttpStatus.BAD_REQUEST);
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_VIDEO_TYPES.containsKey(contentType)) {
            throw new AppException("Only MP4, WEBM, and MOV (QuickTime) videos are supported", HttpStatus.BAD_REQUEST);
        }
    }

    private String buildPublicUrl(String objectName) {
        return "https://storage.googleapis.com/" + bucketName + "/" + objectName;
    }

    private void cleanupObjects(List<String> objectNames) {
        for (String objectName : objectNames) {
            try {
                storage.delete(bucketName, objectName);
            } catch (Exception e) {
                log.warn("Failed to cleanup object {}: {}", objectName, e.getMessage());
            }
        }
    }

    private void requireSafeSegment(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new AppException(label + " is invalid", HttpStatus.BAD_REQUEST);
        }
    }
}
