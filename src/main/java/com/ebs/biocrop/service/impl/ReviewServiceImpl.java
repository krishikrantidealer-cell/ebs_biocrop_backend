package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.CreateReviewRequest;
import com.ebs.biocrop.dto.response.*;
import com.ebs.biocrop.entity.OrderStatus;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.Review;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.ReviewStatus;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.ReviewRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.GcsReviewMediaService;
import com.ebs.biocrop.service.ReviewService;
import com.ebs.biocrop.websocket.WebSocketService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final WebSocketService webSocketService;
    private final GcsReviewMediaService reviewMediaService;

    public ReviewServiceImpl(ReviewRepository reviewRepository,
                             ProductRepository productRepository,
                             OrderRepository orderRepository,
                             UserRepository userRepository,
                             WebSocketService webSocketService,
                             GcsReviewMediaService reviewMediaService) {
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.webSocketService = webSocketService;
        this.reviewMediaService = reviewMediaService;
    }

    @Override
    public ReviewEligibilityResponse checkEligibility(String customerId, String productId) {
        ensureProductExists(productId);
        boolean isVerified = isVerifiedBuyer(customerId, productId);
        Optional<Review> existing = reviewRepository.findByProductIdAndCustomerIdAndIsDeletedFalse(productId, customerId);

        if (!isVerified) {
            return new ReviewEligibilityResponse(
                    false,
                    false,
                    existing.isPresent(),
                    existing.map(Review::getId).orElse(null),
                    "Only verified buyers who purchased this product can write a review"
            );
        }

        if (existing.isPresent()) {
            return new ReviewEligibilityResponse(
                    false,
                    true,
                    true,
                    existing.get().getId(),
                    "You have already reviewed this product. Reviews cannot be edited or resubmitted once posted."
            );
        }

        return new ReviewEligibilityResponse(
                true,
                true,
                false,
                null,
                "You are eligible to review this product"
        );
    }

    @Override
    @Transactional
    public ReviewResponse createReview(String customerId, String productId, CreateReviewRequest request) {
        Product product = ensureProductExists(productId);

        if (!isVerifiedBuyer(customerId, productId)) {
            throw new AppException("Only verified buyers who purchased this product can write a review", HttpStatus.FORBIDDEN);
        }

        if (reviewRepository.findByProductIdAndCustomerIdAndIsDeletedFalse(productId, customerId).isPresent()) {
            throw new AppException("You have already reviewed this product. Reviews cannot be edited or resubmitted once posted.", HttpStatus.CONFLICT);
        }

        validateMediaLimits(request.getImages());

        User user = userRepository.findById(customerId)
                .orElseThrow(() -> new AppException("Customer account not found", HttpStatus.NOT_FOUND));

        Review review = new Review();
        review.setProductId(productId);
        review.setSellerId(product.getSellerId());
        review.setCustomerId(customerId);
        review.setFirstName(user.getFirstName());
        review.setLastName(user.getLastName());
        review.setPhoneNumber(user.getPhoneNumber());

        review.setRating(request.getRating());
        review.setTitle(request.getTitle() != null && !request.getTitle().isBlank() ? request.getTitle().trim() : null);
        review.setComment(request.getComment().trim());
        review.setImages(request.getImages());
        review.setVideo(request.getVideo() != null && !request.getVideo().isBlank() ? request.getVideo().trim() : null);
        review.setIsAnonymous(Boolean.TRUE.equals(request.getIsAnonymous()));
        review.setIsVerifiedBuyer(true);
        review.setStatus(ReviewStatus.APPROVED.name());
        review.setIsDeleted(false);

        LocalDateTime now = LocalDateTime.now();
        review.setCreatedAt(now);
        review.setUpdatedAt(now);

        Review saved = reviewRepository.save(review);

        // Recalculate average rating and totalReviews on product
        recalculateProductRating(product);

        return ReviewResponse.forCustomer(saved);
    }

    @Override
    public ReviewResponse getCustomerReview(String customerId, String productId) {
        ensureProductExists(productId);
        Review review = reviewRepository.findByProductIdAndCustomerIdAndIsDeletedFalse(productId, customerId)
                .orElseThrow(() -> new AppException("Review not found for this product", HttpStatus.NOT_FOUND));
        return ReviewResponse.forCustomer(review);
    }

    @Override
    public ProductReviewsResponse listProductReviews(String productId, Integer rating, Boolean hasMedia, Pageable pageable) {
        Product product = ensureProductExists(productId);

        Page<Review> page;
        if (Boolean.TRUE.equals(hasMedia)) {
            page = reviewRepository.findByProductIdWithMedia(productId, ReviewStatus.APPROVED.name(), pageable);
        } else if (rating != null && rating >= 1 && rating <= 5) {
            page = reviewRepository.findByProductIdAndStatusAndRatingAndIsDeletedFalse(
                    productId, ReviewStatus.APPROVED.name(), rating, pageable);
        } else {
            page = reviewRepository.findByProductIdAndStatusAndIsDeletedFalse(
                    productId, ReviewStatus.APPROVED.name(), pageable);
        }

        Page<ReviewResponse> mapped = page.map(ReviewResponse::forPublic);
        PagedResponse<ReviewResponse> pagedResponse = PagedResponse.from(mapped, r -> r);

        Double avgRating = product.getAverageRating() != null ? product.getAverageRating() : 0.0;
        Long totalReviews = product.getTotalReviews() != null
                ? product.getTotalReviews()
                : reviewRepository.countByProductIdAndStatusAndIsDeletedFalse(productId, ReviewStatus.APPROVED.name());

        return ProductReviewsResponse.of(productId, avgRating, totalReviews, pagedResponse);
    }

    @Override
    public ReviewSummaryResponse getProductReviewSummary(String productId) {
        ensureProductExists(productId);

        List<Review> approvedReviews = reviewRepository.findByProductIdAndStatusAndIsDeletedFalse(
                productId, ReviewStatus.APPROVED.name());

        long totalReviews = approvedReviews.size();
        double avgRating = 0.0;
        Map<String, Long> starDistribution = new LinkedHashMap<>();
        starDistribution.put("5", 0L);
        starDistribution.put("4", 0L);
        starDistribution.put("3", 0L);
        starDistribution.put("2", 0L);
        starDistribution.put("1", 0L);

        long withMediaCount = 0;

        if (totalReviews > 0) {
            long sum = 0;
            for (Review r : approvedReviews) {
                int star = r.getRating() != null ? r.getRating() : 0;
                if (star >= 1 && star <= 5) {
                    sum += star;
                    String key = String.valueOf(star);
                    starDistribution.put(key, starDistribution.get(key) + 1);
                }
                boolean hasImg = r.getImages() != null && !r.getImages().isEmpty();
                boolean hasVid = r.getVideo() != null && !r.getVideo().isBlank();
                if (hasImg || hasVid) {
                    withMediaCount++;
                }
            }
            avgRating = Math.round(((double) sum / totalReviews) * 10.0) / 10.0;
        }

        return new ReviewSummaryResponse(
                productId,
                avgRating,
                totalReviews,
                totalReviews,
                starDistribution,
                starDistribution,
                withMediaCount
        );
    }

    // Seller Operations
    @Override
    public Page<ReviewResponse> listSellerReviews(String sellerId, String productId, Integer rating, Pageable pageable) {
        if (productId != null && !productId.isBlank()) {
            productRepository.findByIdAndSellerId(productId, sellerId)
                    .orElseThrow(() -> new AppException("Product not found or not owned by seller", HttpStatus.FORBIDDEN));

            Page<Review> page = rating != null && rating >= 1 && rating <= 5
                    ? reviewRepository.findBySellerIdAndProductIdAndRatingAndIsDeletedFalse(sellerId, productId, rating, pageable)
                    : reviewRepository.findBySellerIdAndProductIdAndIsDeletedFalse(sellerId, productId, pageable);
            return page.map(ReviewResponse::forSeller);
        }

        Page<Review> page = rating != null && rating >= 1 && rating <= 5
                ? reviewRepository.findBySellerIdAndRatingAndIsDeletedFalse(sellerId, rating, pageable)
                : reviewRepository.findBySellerIdAndIsDeletedFalse(sellerId, pageable);
        return page.map(ReviewResponse::forSeller);
    }

    // Admin Operations
    @Override
    public Page<AdminReviewResponse> listAdminReviews(String productId, String search, Integer rating, Pageable pageable) {
        Page<Review> page;

        if (productId != null && !productId.isBlank()) {
            if (rating != null && rating >= 1 && rating <= 5) {
                page = reviewRepository.findByProductIdAndRatingAndIsDeletedFalse(productId, rating, pageable);
            } else {
                page = reviewRepository.findByProductIdAndIsDeletedFalse(productId, pageable);
            }
        } else if (rating != null && rating >= 1 && rating <= 5) {
            page = reviewRepository.findByRatingAndIsDeletedFalse(rating, pageable);
        } else {
            page = reviewRepository.findByIsDeletedFalse(pageable);
        }

        // Cache product titles for projection efficiency
        Map<String, String> productTitleCache = new HashMap<>();

        List<AdminReviewResponse> mapped = page.getContent().stream().map(review -> {
            String title = productTitleCache.computeIfAbsent(review.getProductId(), pid ->
                    productRepository.findById(pid).map(Product::getTitle).orElse("Product")
            );
            return AdminReviewResponse.from(review, title);
        }).toList();

        // Optional in-memory search filter by product title or customer name if search term provided
        if (search != null && !search.isBlank()) {
            String q = search.trim().toLowerCase();
            List<AdminReviewResponse> filtered = mapped.stream().filter(r ->
                    (r.productTitle() != null && r.productTitle().toLowerCase().contains(q)) ||
                    (r.firstName() != null && r.firstName().toLowerCase().contains(q)) ||
                    (r.lastName() != null && r.lastName().toLowerCase().contains(q)) ||
                    (r.title() != null && r.title().toLowerCase().contains(q)) ||
                    (r.comment() != null && r.comment().toLowerCase().contains(q))
            ).toList();
            return new PageImpl<>(filtered, pageable, filtered.size());
        }

        return new PageImpl<>(mapped, pageable, page.getTotalElements());
    }

    @Override
    public SellerReviewReportResponse getSellerReviewReport(String sellerId) {
        if (sellerId == null || sellerId.isBlank()) {
            throw new AppException("Seller ID is required", HttpStatus.BAD_REQUEST);
        }

        String normalizedSellerId = sellerId.trim();
        List<Product> products = productRepository.findBySellerId(normalizedSellerId);
        Map<String, SellerProductReviewAccumulator> byProduct = new HashMap<>();
        for (Product product : products) {
            byProduct.put(product.getId(), new SellerProductReviewAccumulator(product));
        }

        List<Review> reviews = reviewRepository.findBySellerIdAndIsDeletedFalse(normalizedSellerId);
        SellerProductReviewAccumulator overall = new SellerProductReviewAccumulator(null);
        for (Review review : reviews) {
            SellerProductReviewAccumulator productAccumulator = byProduct.computeIfAbsent(
                    review.getProductId(), ignored -> new SellerProductReviewAccumulator(null));
            productAccumulator.add(review);
            overall.add(review);
        }

        List<SellerReviewReportResponse.ProductPerformance> productReports = byProduct.values().stream()
                .map(SellerProductReviewAccumulator::toProductPerformance)
                .sorted(Comparator.comparing(SellerReviewReportResponse.ProductPerformance::title,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        return new SellerReviewReportResponse(normalizedSellerId, products.size(), reviews.size(),
                overall.approvedReviews, overall.averageRating(), overall.reviewStatusCounts(),
                overall.ratingDistribution(), productReports);
    }

    private static final class SellerProductReviewAccumulator {
        private final Product product;
        private final Map<String, Long> reviewStatusCounts = new LinkedHashMap<>();
        private final Map<String, Long> ratingDistribution = new LinkedHashMap<>();
        private long totalReviews;
        private long approvedReviews;
        private long ratingSum;

        private SellerProductReviewAccumulator(Product product) {
            this.product = product;
            for (ReviewStatus status : ReviewStatus.values()) reviewStatusCounts.put(status.name(), 0L);
            for (int rating = 5; rating >= 1; rating--) ratingDistribution.put(String.valueOf(rating), 0L);
        }

        private void add(Review review) {
            totalReviews++;
            String status = review.getStatus() == null ? "UNKNOWN" : review.getStatus().trim().toUpperCase(Locale.ROOT);
            reviewStatusCounts.merge(status, 1L, Long::sum);
            if (!ReviewStatus.APPROVED.name().equals(status) || review.getRating() == null
                    || review.getRating() < 1 || review.getRating() > 5) return;
            approvedReviews++;
            ratingSum += review.getRating();
            ratingDistribution.merge(String.valueOf(review.getRating()), 1L, Long::sum);
        }

        private double averageRating() {
            return approvedReviews == 0 ? 0.0 : Math.round((double) ratingSum / approvedReviews * 10.0) / 10.0;
        }

        private Map<String, Long> reviewStatusCounts() { return Map.copyOf(reviewStatusCounts); }
        private Map<String, Long> ratingDistribution() { return Map.copyOf(ratingDistribution); }

        private SellerReviewReportResponse.ProductPerformance toProductPerformance() {
            return new SellerReviewReportResponse.ProductPerformance(
                    product == null ? null : product.getId(),
                    product == null ? null : product.getProductCode(),
                    product == null ? null : product.getSku(),
                    product == null ? null : product.getTitle(),
                    product == null ? null : product.getStatus(),
                    product == null ? null : product.getIsAvailable(),
                    totalReviews, approvedReviews, averageRating(), reviewStatusCounts(), ratingDistribution());
        }
    }

    @Override
    @Transactional
    public void adminDeleteReview(String reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new AppException("Review not found with id: " + reviewId, HttpStatus.NOT_FOUND));

        String productId = review.getProductId();

        // Permanently delete review document from reviews collection
        reviewRepository.delete(review);

        // Recalculate product rating
        productRepository.findById(productId).ifPresent(this::recalculateProductRating);

        // Delete any associated images or video in the review bucket
        reviewMediaService.deleteMedia(review.getImages(), review.getVideo());
    }

    private Product ensureProductExists(String productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new AppException("Product not found with id: " + productId, HttpStatus.NOT_FOUND));
    }

    private boolean isVerifiedBuyer(String customerId, String productId) {
        if (customerId == null || productId == null) return false;
        return orderRepository.existsByCustomerIdAndProductIdPurchased(customerId, productId, OrderStatus.CANCELED.name());
    }

    private void validateMediaLimits(List<String> images) {
        if (images != null && images.size() > 5) {
            throw new AppException("Maximum 5 images are allowed per review", HttpStatus.BAD_REQUEST);
        }
    }

    private void recalculateProductRating(Product product) {
        List<Review> approvedReviews = reviewRepository.findByProductIdAndStatusAndIsDeletedFalse(
                product.getId(), ReviewStatus.APPROVED.name());

        double avgRating = 0.0;
        long count = approvedReviews.size();

        if (count > 0) {
            double sum = approvedReviews.stream()
                    .mapToInt(r -> r.getRating() != null ? r.getRating() : 0)
                    .sum();
            avgRating = Math.round((sum / count) * 10.0) / 10.0;
        }

        product.setAverageRating(avgRating);
        product.setTotalReviews(count);
        productRepository.save(product);

        // Real-time broadcast to WebSocket listeners
        webSocketService.broadcast("products:rating_updated", Map.of(
                "productId", product.getId(),
                "averageRating", avgRating,
                "totalReviews", count,
                "reviewCount", count,
                "count", count
        ));
    }
}
