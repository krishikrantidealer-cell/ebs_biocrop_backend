package com.ebs.biocrop;

import com.ebs.biocrop.dto.response.AdminReviewResponse;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminReviewControllerTest {

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

    private CustomUserDetails createMockAdminDetails() {
        User u = new User();
        u.setId("admin123");
        u.setRole(UserRole.ROLE_ADMIN);
        return CustomUserDetails.build(u);
    }

    @Test
    void listAdminReviews_ShouldReturnPagedAdminReviews() throws Exception {
        CustomUserDetails admin = createMockAdminDetails();
        AdminReviewResponse sample = new AdminReviewResponse(
                "rev123",
                "prod123",
                "Organic Wheat Seeds",
                "seller123",
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

        when(reviewService.listAdminReviews(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sample)));

        mockMvc.perform(get("/api/v1/admin/reviews")
                        .with(user(admin))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value("rev123"))
                .andExpect(jsonPath("$.data.content[0].productTitle").value("Organic Wheat Seeds"))
                .andExpect(jsonPath("$.data.content[0].phoneNumber").value("9876543210"));
    }

    @Test
    void deleteReviewPermanently_ShouldSucceed() throws Exception {
        CustomUserDetails admin = createMockAdminDetails();
        doNothing().when(reviewService).adminDeleteReview("rev123");

        mockMvc.perform(delete("/api/v1/admin/reviews/rev123")
                        .with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void getSellerReviewReport_ShouldReturnReport() throws Exception {
        CustomUserDetails admin = createMockAdminDetails();
        com.ebs.biocrop.dto.response.SellerReviewReportResponse mockReport = new com.ebs.biocrop.dto.response.SellerReviewReportResponse(
                "seller123", 5, 20, 18, 4.8,
                java.util.Map.of("APPROVED", 18L),
                java.util.Map.of("5", 15L, "4", 3L),
                List.of()
        );

        when(reviewService.getSellerReviewReport("seller123")).thenReturn(mockReport);

        mockMvc.perform(get("/api/v1/admin/reviews/seller-report")
                        .param("sellerId", "seller123")
                        .with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sellerId").value("seller123"))
                .andExpect(jsonPath("$.data.totalProducts").value(5))
                .andExpect(jsonPath("$.data.averageRating").value(4.8));
    }
}
