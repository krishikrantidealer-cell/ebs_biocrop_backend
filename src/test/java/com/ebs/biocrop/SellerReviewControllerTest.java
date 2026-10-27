package com.ebs.biocrop;

import com.ebs.biocrop.dto.response.ReviewResponse;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.ReviewStatus;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.RateLimitService;
import com.ebs.biocrop.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SellerReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewService reviewService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private RateLimitService rateLimitService;

    private CustomUserDetails createMockSellerDetails() {
        User u = new User();
        u.setId("seller123");
        u.setRole(UserRole.ROLE_SELLER);
        return CustomUserDetails.build(u);
    }

    @Test
    void listSellerReviews_ShouldReturnSellerReviews() throws Exception {
        CustomUserDetails seller = createMockSellerDetails();
        ReviewResponse sample = new ReviewResponse(
                "rev123",
                "prod123",
                "cust123",
                "Ramesh",
                "Kumar",
                "9876543210",
                5,
                "Superb",
                "Good crops",
                List.of(),
                null,
                false,
                true,
                ReviewStatus.APPROVED.name(),
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(reviewService.listSellerReviews(eq("seller123"), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sample)));

        mockMvc.perform(get("/api/v1/seller/reviews")
                        .with(user(seller))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value("rev123"))
                .andExpect(jsonPath("$.data.content[0].rating").value(5));
    }

    @Test
    void listSellerProductReviews_ShouldReturnReviewsForProduct() throws Exception {
        CustomUserDetails seller = createMockSellerDetails();
        ReviewResponse sample = new ReviewResponse(
                "rev123",
                "prod123",
                "cust123",
                "Ramesh",
                "Kumar",
                "9876543210",
                5,
                "Superb",
                "Good crops",
                List.of(),
                null,
                false,
                true,
                ReviewStatus.APPROVED.name(),
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(reviewService.listSellerReviews(eq("seller123"), eq("prod123"), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sample)));

        mockMvc.perform(get("/api/v1/seller/products/prod123/reviews")
                        .with(user(seller))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value("rev123"))
                .andExpect(jsonPath("$.data.content[0].productId").value("prod123"));
    }
}
