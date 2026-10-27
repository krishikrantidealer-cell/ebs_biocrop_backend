package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.CreateReviewRequest;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.ProductReviewsResponse;
import com.ebs.biocrop.dto.response.ReviewEligibilityResponse;
import com.ebs.biocrop.dto.response.ReviewResponse;
import com.ebs.biocrop.dto.response.ReviewSummaryResponse;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.ReviewStatus;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.RateLimitService;
import com.ebs.biocrop.service.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReviewService reviewService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private com.ebs.biocrop.service.GcsReviewMediaService reviewMediaService;

    private ReviewResponse createSampleReviewResponse() {
        return new ReviewResponse(
                "rev123",
                "prod123",
                "cust123",
                "Ramesh",
                "Kumar",
                "9876543210",
                5,
                "Excellent germination quality!",
                "Grew healthy crops within weeks. Highly recommended organic seeds.",
                List.of("https://storage.googleapis.com/krishibhandar/reviews/review1.webp"),
                "https://storage.googleapis.com/krishibhandar/reviews/review1.mp4",
                false,
                true,
                ReviewStatus.APPROVED.name(),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private CustomUserDetails createMockCustomerDetails(String id) {
        User u = new User();
        u.setId(id);
        u.setFirstName("Ramesh");
        u.setLastName("Kumar");
        u.setRole(UserRole.ROLE_CUSTOMER);
        return CustomUserDetails.build(u);
    }

    @Test
    void getPublicReviews_ShouldSucceedWithoutAuth_AndContainCount() throws Exception {
        PagedResponse<ReviewResponse> paged = new PagedResponse<>(
                List.of(createSampleReviewResponse()),
                0,
                10,
                1L,
                1,
                true,
                true
        );
        ProductReviewsResponse reviewsResponse = ProductReviewsResponse.of("prod123", 4.8, 1L, paged);

        when(reviewService.listProductReviews(eq("prod123"), any(), any(), any(Pageable.class)))
                .thenReturn(reviewsResponse);

        mockMvc.perform(get("/api/v1/products/prod123/reviews")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value("prod123"))
                .andExpect(jsonPath("$.data.totalReviews").value(1))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.averageRating").value(4.8))
                .andExpect(jsonPath("$.data.content[0].id").value("rev123"))
                .andExpect(jsonPath("$.data.content[0].firstName").value("Ramesh"))
                .andExpect(jsonPath("$.data.content[0].lastName").value("Kumar"))
                .andExpect(jsonPath("$.data.content[0].rating").value(5))
                .andExpect(jsonPath("$.data.content[0].title").value("Excellent germination quality!"));
    }

    @Test
    void getReviewsSummary_ShouldSucceedWithoutAuth_AndContainCount() throws Exception {
        ReviewSummaryResponse summary = new ReviewSummaryResponse(
                "prod123",
                4.8,
                10L,
                10L,
                Map.of("5", 8L, "4", 2L, "3", 0L, "2", 0L, "1", 0L),
                Map.of("5", 8L, "4", 2L, "3", 0L, "2", 0L, "1", 0L),
                3L
        );

        when(reviewService.getProductReviewSummary("prod123")).thenReturn(summary);

        mockMvc.perform(get("/api/v1/products/prod123/reviews/summary")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value("prod123"))
                .andExpect(jsonPath("$.data.averageRating").value(4.8))
                .andExpect(jsonPath("$.data.totalReviews").value(10))
                .andExpect(jsonPath("$.data.count").value(10))
                .andExpect(jsonPath("$.data.withMediaCount").value(3));
    }

    @Test
    void checkEligibility_WithoutAuth_ShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/products/prod123/reviews/eligibility"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checkEligibility_WithCustomerRole_ShouldReturnEligibility() throws Exception {
        CustomUserDetails customer = createMockCustomerDetails("cust123");
        ReviewEligibilityResponse eligibility = new ReviewEligibilityResponse(
                true,
                true,
                false,
                null,
                "You are eligible to review this product"
        );

        when(reviewService.checkEligibility("cust123", "prod123")).thenReturn(eligibility);

        mockMvc.perform(get("/api/v1/products/prod123/reviews/eligibility")
                        .with(user(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(true))
                .andExpect(jsonPath("$.data.isVerifiedBuyer").value(true));
    }

    @Test
    void createReview_ValidRequest_ShouldReturn201() throws Exception {
        CustomUserDetails customer = createMockCustomerDetails("cust123");

        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating(5);
        request.setTitle("Excellent germination quality!");
        request.setComment("Grew healthy crops within weeks. Highly recommended organic seeds.");
        request.setImages(List.of("https://storage.googleapis.com/krishibhandar/reviews/review1.webp"));
        request.setVideo("https://storage.googleapis.com/krishibhandar/reviews/review1.mp4");
        request.setIsAnonymous(false);

        ReviewResponse response = createSampleReviewResponse();
        when(reviewService.createReview(eq("cust123"), eq("prod123"), any(CreateReviewRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/products/prod123/reviews")
                        .with(user(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("rev123"))
                .andExpect(jsonPath("$.data.customerId").value("cust123"))
                .andExpect(jsonPath("$.data.firstName").value("Ramesh"))
                .andExpect(jsonPath("$.data.lastName").value("Kumar"))
                .andExpect(jsonPath("$.data.phoneNumber").value("9876543210"))
                .andExpect(jsonPath("$.data.rating").value(5))
                .andExpect(jsonPath("$.data.isVerifiedBuyer").value(true))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    void createReview_InvalidRating_ShouldReturn400() throws Exception {
        CustomUserDetails customer = createMockCustomerDetails("cust123");

        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating(6); // Invalid rating > 5
        request.setTitle("Good");
        request.setComment("Valid comment here");

        mockMvc.perform(post("/api/v1/products/prod123/reviews")
                        .with(user(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReview_WhenNotVerifiedBuyer_ShouldReturn403() throws Exception {
        CustomUserDetails customer = createMockCustomerDetails("cust123");

        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating(5);
        request.setTitle("Good");
        request.setComment("Valid comment here");

        when(reviewService.createReview(eq("cust123"), eq("prod123"), any(CreateReviewRequest.class)))
                .thenThrow(new AppException("Only verified buyers who purchased this product can write a review", HttpStatus.FORBIDDEN));

        mockMvc.perform(post("/api/v1/products/prod123/reviews")
                        .with(user(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Only verified buyers who purchased this product can write a review"));
    }

    @Test
    void getMyReview_ShouldReturnUserReview() throws Exception {
        CustomUserDetails customer = createMockCustomerDetails("cust123");

        when(reviewService.getCustomerReview("cust123", "prod123")).thenReturn(createSampleReviewResponse());

        mockMvc.perform(get("/api/v1/products/prod123/reviews/my-review")
                        .with(user(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("rev123"))
                .andExpect(jsonPath("$.data.customerId").value("cust123"))
                .andExpect(jsonPath("$.data.firstName").value("Ramesh"))
                .andExpect(jsonPath("$.data.lastName").value("Kumar"))
                .andExpect(jsonPath("$.data.rating").value(5));
    }

    @Test
    void uploadReviewMedia_ShouldSucceedForEligibleCustomer() throws Exception {
        CustomUserDetails customer = createMockCustomerDetails("cust123");

        when(reviewService.checkEligibility("cust123", "prod123"))
                .thenReturn(new ReviewEligibilityResponse(true, true, false, null, "Eligible to review"));

        org.springframework.mock.web.MockMultipartFile image = new org.springframework.mock.web.MockMultipartFile(
                "images", "test.jpg", "image/jpeg", new byte[]{1, 2, 3});

        when(reviewMediaService.uploadReviewMedia(eq("prod123"), eq("cust123"), any(), any()))
                .thenReturn(com.ebs.biocrop.dto.response.ReviewMediaUploadResponse.of(
                        List.of("https://storage.googleapis.com/sellerhub-prod-review-media-us/reviews/prod123/cust123/images/img1.jpg"),
                        null
                ));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/products/prod123/reviews/media")
                        .file(image)
                        .with(user(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Review media uploaded successfully"))
                .andExpect(jsonPath("$.data.images[0]").value("https://storage.googleapis.com/sellerhub-prod-review-media-us/reviews/prod123/cust123/images/img1.jpg"));
    }

    @Test
    void uploadReviewMedia_WithMediaCapitalKey_ShouldAlsoSucceed() throws Exception {
        CustomUserDetails customer = createMockCustomerDetails("cust123");

        when(reviewService.checkEligibility("cust123", "prod123"))
                .thenReturn(new ReviewEligibilityResponse(true, true, false, null, "Eligible to review"));

        org.springframework.mock.web.MockMultipartFile mediaFile = new org.springframework.mock.web.MockMultipartFile(
                "Media", "test.jpg", "image/jpeg", new byte[]{1, 2, 3});

        when(reviewMediaService.uploadReviewMedia(eq("prod123"), eq("cust123"), any(), any()))
                .thenReturn(com.ebs.biocrop.dto.response.ReviewMediaUploadResponse.of(
                        List.of("https://storage.googleapis.com/sellerhub-prod-review-media-us/reviews/prod123/cust123/images/img1.jpg"),
                        null
                ));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/products/prod123/reviews/media")
                        .file(mediaFile)
                        .with(user(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Review media uploaded successfully"));
    }

    @Test
    void createReviewMultipart_ShouldUploadMediaAndCreateReview() throws Exception {
        CustomUserDetails customer = createMockCustomerDetails("cust123");

        org.springframework.mock.web.MockMultipartFile image = new org.springframework.mock.web.MockMultipartFile(
                "images", "farm.jpg", "image/jpeg", new byte[]{1, 2, 3});

        when(reviewMediaService.uploadReviewMedia(eq("prod123"), eq("cust123"), any(), any()))
                .thenReturn(com.ebs.biocrop.dto.response.ReviewMediaUploadResponse.of(
                        List.of("https://storage.googleapis.com/sellerhub-prod-review-media-us/reviews/prod123/cust123/images/farm.jpg"),
                        null
                ));

        when(reviewService.createReview(eq("cust123"), eq("prod123"), any(CreateReviewRequest.class)))
                .thenReturn(createSampleReviewResponse());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/products/prod123/reviews")
                        .file(image)
                        .param("rating", "5")
                        .param("title", "Great germination")
                        .param("comment", "Healthy crops grown in 2 weeks")
                        .param("isAnonymous", "false")
                        .with(user(customer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Review submitted successfully"))
                .andExpect(jsonPath("$.data.rating").value(5));
    }
}
