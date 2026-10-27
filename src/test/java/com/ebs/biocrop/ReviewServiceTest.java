package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.CreateReviewRequest;
import com.ebs.biocrop.dto.response.AdminReviewResponse;
import com.ebs.biocrop.dto.response.ProductReviewsResponse;
import com.ebs.biocrop.dto.response.ReviewEligibilityResponse;
import com.ebs.biocrop.dto.response.ReviewResponse;
import com.ebs.biocrop.dto.response.ReviewSummaryResponse;
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
import com.ebs.biocrop.service.impl.ReviewServiceImpl;
import com.ebs.biocrop.websocket.WebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WebSocketService webSocketService;

    @Mock
    private com.ebs.biocrop.service.GcsReviewMediaService reviewMediaService;

    @InjectMocks
    private ReviewServiceImpl reviewService;

    private User sampleUser;
    private Product sampleProduct;

    @BeforeEach
    void setUp() {
        sampleUser = new User();
        sampleUser.setId("cust123");
        sampleUser.setFirstName("Ramesh");
        sampleUser.setLastName("Kumar");
        sampleUser.setPhoneNumber("9876543210");

        sampleProduct = new Product();
        sampleProduct.setId("prod123");
        sampleProduct.setSellerId("seller123");
        sampleProduct.setTitle("Organic Wheat Seeds");
        sampleProduct.setAverageRating(0.0);
        sampleProduct.setTotalReviews(0L);
    }

    @Test
    void createReview_Successful_ForVerifiedBuyer() {
        when(productRepository.findById("prod123")).thenReturn(Optional.of(sampleProduct));
        when(userRepository.findById("cust123")).thenReturn(Optional.of(sampleUser));
        when(orderRepository.existsByCustomerIdAndProductIdPurchased("cust123", "prod123", OrderStatus.CANCELED.name()))
                .thenReturn(true);
        when(reviewRepository.findByProductIdAndCustomerIdAndIsDeletedFalse("prod123", "cust123"))
                .thenReturn(Optional.empty());

        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> {
            Review r = invocation.getArgument(0);
            r.setId("rev001");
            return r;
        });

        Review r1 = new Review();
        r1.setRating(5);
        when(reviewRepository.findByProductIdAndStatusAndIsDeletedFalse("prod123", ReviewStatus.APPROVED.name()))
                .thenReturn(List.of(r1));

        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating(5);
        request.setTitle("Superb Quality!");
        request.setComment("High germination rate, healthy seedlings.");
        request.setImages(List.of("https://example.com/img1.webp"));
        request.setVideo("https://example.com/vid1.mp4");
        request.setIsAnonymous(false);

        ReviewResponse response = reviewService.createReview("cust123", "prod123", request);

        assertNotNull(response);
        assertEquals("rev001", response.id());
        assertEquals("prod123", response.productId());
        assertEquals("cust123", response.customerId());
        assertEquals("Ramesh", response.firstName());
        assertEquals("Kumar", response.lastName());
        assertEquals("9876543210", response.phoneNumber());
        assertEquals(5, response.rating());
        assertEquals("Superb Quality!", response.title());
        assertEquals("https://example.com/vid1.mp4", response.video());
        assertTrue(response.isVerifiedBuyer());
        assertEquals(ReviewStatus.APPROVED.name(), response.status());

        verify(webSocketService, times(1)).broadcast(eq("products:rating_updated"), any());
    }

    @Test
    void createReview_Fails_WhenNotVerifiedBuyer() {
        when(productRepository.findById("prod123")).thenReturn(Optional.of(sampleProduct));
        when(orderRepository.existsByCustomerIdAndProductIdPurchased("cust123", "prod123", OrderStatus.CANCELED.name()))
                .thenReturn(false);

        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating(5);
        request.setTitle("Good");
        request.setComment("Tried to review without buying");

        AppException ex = assertThrows(AppException.class, () ->
                reviewService.createReview("cust123", "prod123", request));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertTrue(ex.getMessage().contains("Only verified buyers"));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createReview_Fails_WhenMoreThan5Images() {
        when(productRepository.findById("prod123")).thenReturn(Optional.of(sampleProduct));
        when(orderRepository.existsByCustomerIdAndProductIdPurchased("cust123", "prod123", OrderStatus.CANCELED.name()))
                .thenReturn(true);

        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating(4);
        request.setComment("Sample review");
        request.setImages(List.of("img1", "img2", "img3", "img4", "img5", "img6"));

        AppException ex = assertThrows(AppException.class, () ->
                reviewService.createReview("cust123", "prod123", request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("Maximum 5 images are allowed"));
    }

    @Test
    void createReview_Fails_WhenAlreadyReviewed() {
        when(productRepository.findById("prod123")).thenReturn(Optional.of(sampleProduct));
        when(orderRepository.existsByCustomerIdAndProductIdPurchased("cust123", "prod123", OrderStatus.CANCELED.name()))
                .thenReturn(true);

        Review existingReview = new Review();
        existingReview.setId("existingRev1");
        when(reviewRepository.findByProductIdAndCustomerIdAndIsDeletedFalse("prod123", "cust123"))
                .thenReturn(Optional.of(existingReview));

        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating(5);
        request.setComment("Duplicate review attempt");

        AppException ex = assertThrows(AppException.class, () ->
                reviewService.createReview("cust123", "prod123", request));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertTrue(ex.getMessage().contains("already reviewed"));
    }

    @Test
    void checkEligibility_ReturnsTrueForVerifiedBuyerWithoutReview() {
        when(productRepository.findById("prod123")).thenReturn(Optional.of(sampleProduct));
        when(orderRepository.existsByCustomerIdAndProductIdPurchased("cust123", "prod123", OrderStatus.CANCELED.name()))
                .thenReturn(true);
        when(reviewRepository.findByProductIdAndCustomerIdAndIsDeletedFalse("prod123", "cust123"))
                .thenReturn(Optional.empty());

        ReviewEligibilityResponse response = reviewService.checkEligibility("cust123", "prod123");

        assertTrue(response.eligible());
        assertTrue(response.isVerifiedBuyer());
        assertFalse(response.hasReviewed());
        assertNull(response.existingReviewId());
    }

    @Test
    void checkEligibility_ReturnsFalseForNonBuyer() {
        when(productRepository.findById("prod123")).thenReturn(Optional.of(sampleProduct));
        when(orderRepository.existsByCustomerIdAndProductIdPurchased("cust123", "prod123", OrderStatus.CANCELED.name()))
                .thenReturn(false);

        ReviewEligibilityResponse response = reviewService.checkEligibility("cust123", "prod123");

        assertFalse(response.eligible());
        assertFalse(response.isVerifiedBuyer());
        assertNotNull(response.message());
    }

    @Test
    void checkEligibility_ReturnsAlreadyReviewedWhenExists() {
        when(productRepository.findById("prod123")).thenReturn(Optional.of(sampleProduct));
        when(orderRepository.existsByCustomerIdAndProductIdPurchased("cust123", "prod123", OrderStatus.CANCELED.name()))
                .thenReturn(true);

        Review rev = new Review();
        rev.setId("rev001");
        when(reviewRepository.findByProductIdAndCustomerIdAndIsDeletedFalse("prod123", "cust123"))
                .thenReturn(Optional.of(rev));

        ReviewEligibilityResponse response = reviewService.checkEligibility("cust123", "prod123");

        assertFalse(response.eligible());
        assertTrue(response.isVerifiedBuyer());
        assertTrue(response.hasReviewed());
        assertEquals("rev001", response.existingReviewId());
        assertTrue(response.message().contains("Reviews cannot be edited or resubmitted"));
    }

    @Test
    void listProductReviews_ReturnsTotalReviewsAndCount() {
        when(productRepository.findById("prod123")).thenReturn(Optional.of(sampleProduct));

        Review r1 = new Review();
        r1.setId("rev001");
        r1.setProductId("prod123");
        r1.setCustomerId("cust123");
        r1.setFirstName("Ramesh");
        r1.setLastName("Kumar");
        r1.setRating(5);

        when(reviewRepository.findByProductIdAndStatusAndIsDeletedFalse(eq("prod123"), eq(ReviewStatus.APPROVED.name()), any()))
                .thenReturn(new PageImpl<>(List.of(r1)));

        ProductReviewsResponse response = reviewService.listProductReviews("prod123", null, null, PageRequest.of(0, 10));

        assertNotNull(response);
        assertEquals("prod123", response.productId());
        assertEquals(0L, response.totalReviews());
        assertEquals(0L, response.count());
        assertEquals(1, response.content().size());
        assertEquals("rev001", response.content().get(0).id());
        assertEquals("Ramesh", response.content().get(0).firstName());
        assertNull(response.content().get(0).phoneNumber()); // Verified sensitive info hidden in public response!
    }

    @Test
    void getProductReviewsSummary_CalculatesAccurateDistribution() {
        when(productRepository.findById("prod123")).thenReturn(Optional.of(sampleProduct));

        Review r1 = new Review();
        r1.setRating(5);
        r1.setImages(List.of("img1.webp"));

        Review r2 = new Review();
        r2.setRating(5);

        Review r3 = new Review();
        r3.setRating(4);

        Review r4 = new Review();
        r4.setRating(3);

        when(reviewRepository.findByProductIdAndStatusAndIsDeletedFalse("prod123", ReviewStatus.APPROVED.name()))
                .thenReturn(List.of(r1, r2, r3, r4));

        ReviewSummaryResponse summary = reviewService.getProductReviewSummary("prod123");

        assertEquals("prod123", summary.productId());
        assertEquals(4L, summary.totalReviews());
        assertEquals(4L, summary.count());
        assertEquals(1L, summary.withMediaCount());
        assertEquals(4.3, summary.averageRating());
        assertEquals(2L, summary.starDistribution().get("5"));
    }

    @Test
    void listSellerReviews_ShouldReturnSellerProductsReviews() {
        Review r1 = new Review();
        r1.setId("rev001");
        r1.setSellerId("seller123");
        r1.setProductId("prod123");
        r1.setRating(5);
        r1.setFirstName("Ramesh");
        r1.setLastName("Kumar");

        when(reviewRepository.findBySellerIdAndIsDeletedFalse(eq("seller123"), any()))
                .thenReturn(new PageImpl<>(List.of(r1)));

        Page<ReviewResponse> result = reviewService.listSellerReviews("seller123", null, null, PageRequest.of(0, 10));

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals("rev001", result.getContent().get(0).id());
    }

    @Test
    void adminDeleteReview_ShouldPermanentlyDeleteAndRecalculate() {
        Review r1 = new Review();
        r1.setId("rev001");
        r1.setProductId("prod123");
        r1.setRating(5);

        when(reviewRepository.findById("rev001")).thenReturn(Optional.of(r1));
        when(productRepository.findById("prod123")).thenReturn(Optional.of(sampleProduct));
        when(reviewRepository.findByProductIdAndStatusAndIsDeletedFalse("prod123", ReviewStatus.APPROVED.name()))
                .thenReturn(Collections.emptyList());

        reviewService.adminDeleteReview("rev001");

        verify(reviewRepository, times(1)).delete(r1);
        verify(reviewMediaService, times(1)).deleteMedia(any(), any());
        verify(productRepository, times(1)).save(sampleProduct);
        assertEquals(0.0, sampleProduct.getAverageRating());
        assertEquals(0L, sampleProduct.getTotalReviews());
        verify(webSocketService, times(1)).broadcast(eq("products:rating_updated"), any());
    }
}
