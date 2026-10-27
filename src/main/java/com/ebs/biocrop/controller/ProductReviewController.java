package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.CreateReviewRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.ProductReviewsResponse;
import com.ebs.biocrop.dto.response.ReviewEligibilityResponse;
import com.ebs.biocrop.dto.response.ReviewMediaUploadResponse;
import com.ebs.biocrop.dto.response.ReviewResponse;
import com.ebs.biocrop.dto.response.ReviewSummaryResponse;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.GcsReviewMediaService;
import com.ebs.biocrop.service.RateLimitGuard;
import com.ebs.biocrop.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/products/{productId}/reviews")
@Tag(name = "Product Reviews", description = "Customer product reviews and ratings")
public class ProductReviewController {

    private final ReviewService reviewService;
    private final RateLimitGuard rateLimitGuard;
    private final GcsReviewMediaService reviewMediaService;

    public ProductReviewController(ReviewService reviewService, RateLimitGuard rateLimitGuard, GcsReviewMediaService reviewMediaService) {
        this.reviewService = reviewService;
        this.rateLimitGuard = rateLimitGuard;
        this.reviewMediaService = reviewMediaService;
    }

    // Public: List product reviews with pagination, ratings, and total review count
    @GetMapping
    @Operation(summary = "List approved reviews for a product with pagination, ratings, and total review count")
    public ResponseEntity<ApiResponse<ProductReviewsResponse>> listProductReviews(
            @PathVariable String productId,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) Boolean hasMedia,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        int validPage = Math.max(0, page);
        int validSize = Math.min(50, Math.max(1, size));
        PageRequest pageRequest = PageRequest.of(validPage, validSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        ProductReviewsResponse result = reviewService.listProductReviews(productId, rating, hasMedia, pageRequest);
        return ResponseEntity.ok(ApiResponse.ok("Reviews retrieved successfully", result));
    }

    // Public: Get rating summary, totalReviews count, and star distribution breakdown
    @GetMapping("/summary")
    @Operation(summary = "Get review statistics, totalReviews count, and star distribution for a product")
    public ResponseEntity<ApiResponse<ReviewSummaryResponse>> getReviewSummary(@PathVariable String productId) {
        return ResponseEntity.ok(ApiResponse.ok("Review summary retrieved successfully",
                reviewService.getProductReviewSummary(productId)));
    }

    // Customer: Check eligibility to write a review (verified buyer check)
    @GetMapping("/eligibility")
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Check if authenticated customer can review this product")
    public ResponseEntity<ApiResponse<ReviewEligibilityResponse>> checkEligibility(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String productId) {

        return ResponseEntity.ok(ApiResponse.ok("Eligibility checked successfully",
                reviewService.checkEligibility(customer.getId(), productId)));
    }

    // Customer: Submit a new review via JSON (Immutable once posted)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a product review via JSON (Verified buyers only; cannot be edited or deleted once posted)")
    public ResponseEntity<ApiResponse<ReviewResponse>> createReviewJson(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String productId,
            @Valid @RequestBody CreateReviewRequest request) {

        rateLimitGuard.enforce("customer-review-write", customer.getId(), 10, Duration.ofMinutes(1));
        ReviewResponse created = reviewService.createReview(customer.getId(), productId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Review submitted successfully", created));
    }

    // Customer: Submit a new review with direct image/video file attachments via multipart/form-data
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a product review with direct media file uploads (Multipart form-data; Verified buyers only)")
    public ResponseEntity<ApiResponse<ReviewResponse>> createReviewMultipart(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String productId,
            @RequestParam("rating") Integer rating,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam("comment") String comment,
            @RequestParam(value = "isAnonymous", required = false, defaultValue = "false") Boolean isAnonymous,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "images (or Media)", required = false) List<MultipartFile> imagesOrMedia,
            @RequestParam(value = "media", required = false) List<MultipartFile> media,
            @RequestParam(value = "Media", required = false) List<MultipartFile> mediaCapital,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "file", required = false) List<MultipartFile> file,
            @RequestParam(value = "video", required = false) MultipartFile video,
            @RequestParam(value = "Video", required = false) MultipartFile videoCapital) {

        rateLimitGuard.enforce("customer-review-write", customer.getId(), 10, Duration.ofMinutes(1));

        if (rating == null || rating < 1 || rating > 5) {
            throw new AppException("Rating is required and must be between 1 and 5", HttpStatus.BAD_REQUEST);
        }
        if (comment == null || comment.isBlank()) {
            throw new AppException("Review comment is required", HttpStatus.BAD_REQUEST);
        }

        List<MultipartFile> candidateFiles = new ArrayList<>();
        if (images != null) candidateFiles.addAll(images);
        if (imagesOrMedia != null) candidateFiles.addAll(imagesOrMedia);
        if (media != null) candidateFiles.addAll(media);
        if (mediaCapital != null) candidateFiles.addAll(mediaCapital);
        if (files != null) candidateFiles.addAll(files);
        if (file != null) candidateFiles.addAll(file);

        MultipartFile effectiveVideo = video != null ? video : videoCapital;
        List<MultipartFile> effectiveImages = new ArrayList<>();

        for (MultipartFile f : candidateFiles) {
            if (f != null && !f.isEmpty()) {
                String contentType = f.getContentType() == null ? "" : f.getContentType().toLowerCase(Locale.ROOT);
                if (contentType.startsWith("video/") && effectiveVideo == null) {
                    effectiveVideo = f;
                } else {
                    effectiveImages.add(f);
                }
            }
        }

        // Upload media to GCS bucket sellerhub-prod-review-media-us if any file was attached
        List<String> uploadedImageUrls = new ArrayList<>();
        String uploadedVideoUrl = null;

        if (!effectiveImages.isEmpty() || (effectiveVideo != null && !effectiveVideo.isEmpty())) {
            ReviewMediaUploadResponse uploadRes = reviewMediaService.uploadReviewMedia(
                    productId, customer.getId(), effectiveImages, effectiveVideo);
            if (uploadRes.images() != null) {
                uploadedImageUrls.addAll(uploadRes.images());
            }
            uploadedVideoUrl = uploadRes.video();
        }

        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating(rating);
        request.setTitle(title);
        request.setComment(comment);
        request.setImages(uploadedImageUrls);
        request.setVideo(uploadedVideoUrl);
        request.setIsAnonymous(isAnonymous);

        ReviewResponse created = reviewService.createReview(customer.getId(), productId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Review submitted successfully", created));
    }

    // Customer: Upload review images (up to 5) and/or video (up to 1) to GCS bucket sellerhub-prod-review-media-us
    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Upload review images (max 5) and video (max 1) for a product review")
    public ResponseEntity<ApiResponse<ReviewMediaUploadResponse>> uploadReviewMedia(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String productId,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "images (or Media)", required = false) List<MultipartFile> imagesOrMedia,
            @RequestParam(value = "media", required = false) List<MultipartFile> media,
            @RequestParam(value = "Media", required = false) List<MultipartFile> mediaCapital,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "file", required = false) List<MultipartFile> file,
            @RequestParam(value = "video", required = false) MultipartFile video,
            @RequestParam(value = "Video", required = false) MultipartFile videoCapital) {

        rateLimitGuard.enforce("customer-review-media-upload", customer.getId(), 10, Duration.ofMinutes(1));

        ReviewEligibilityResponse eligibility = reviewService.checkEligibility(customer.getId(), productId);
        if (!eligibility.eligible()) {
            throw new AppException(eligibility.message(), HttpStatus.FORBIDDEN);
        }

        List<MultipartFile> candidateFiles = new ArrayList<>();
        if (images != null) candidateFiles.addAll(images);
        if (imagesOrMedia != null) candidateFiles.addAll(imagesOrMedia);
        if (media != null) candidateFiles.addAll(media);
        if (mediaCapital != null) candidateFiles.addAll(mediaCapital);
        if (files != null) candidateFiles.addAll(files);
        if (file != null) candidateFiles.addAll(file);

        MultipartFile effectiveVideo = video != null ? video : videoCapital;
        List<MultipartFile> effectiveImages = new ArrayList<>();

        for (MultipartFile f : candidateFiles) {
            if (f != null && !f.isEmpty()) {
                String contentType = f.getContentType() == null ? "" : f.getContentType().toLowerCase(Locale.ROOT);
                if (contentType.startsWith("video/") && effectiveVideo == null) {
                    effectiveVideo = f;
                } else {
                    effectiveImages.add(f);
                }
            }
        }

        ReviewMediaUploadResponse response = reviewMediaService.uploadReviewMedia(productId, customer.getId(), effectiveImages, effectiveVideo);
        return ResponseEntity.ok(ApiResponse.ok("Review media uploaded successfully", response));
    }

    // Customer: Get own review for this product
    @GetMapping("/my-review")
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get authenticated customer's own review for this product")
    public ResponseEntity<ApiResponse<ReviewResponse>> getMyReview(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String productId) {

        return ResponseEntity.ok(ApiResponse.ok("Review retrieved successfully",
                reviewService.getCustomerReview(customer.getId(), productId)));
    }
}
