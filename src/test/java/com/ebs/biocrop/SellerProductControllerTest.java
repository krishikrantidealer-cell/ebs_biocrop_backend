package com.ebs.biocrop;

import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductDimensions;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.RateLimitService;
import com.ebs.biocrop.service.SellerProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SellerProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SellerProductService sellerProductService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private RateLimitService rateLimitService;

    private CustomUserDetails createMockSellerDetails(String sellerId) {
        User u = new User();
        u.setId(sellerId);
        u.setRole(UserRole.ROLE_SELLER);
        return CustomUserDetails.build(u);
    }

    private Product createSampleProduct(String sellerId, String productId) {
        Product p = new Product();
        p.setId(productId);
        p.setSellerId(sellerId);
        p.setProductCode("PRD-" + sellerId + "-000001");
        p.setSku("SKU-BIO-001");
        p.setTitle("Bio Crop Fertilizer");
        p.setCategoryId("cat123");
        p.setStatus("INACTIVE");
        p.setIsAvailable(false);
        p.setDimensions(new ProductDimensions(10.0, 10.0, 10.0, "cm"));
        p.setAverageRating(4.5);
        p.setTotalReviews(10L);
        p.setProductImages(new ArrayList<>());
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return p;
    }

    @Test
    void listProducts_ShouldReturnSellerListings() throws Exception {
        CustomUserDetails seller = createMockSellerDetails("seller123");
        Product p = createSampleProduct("seller123", "prod123");

        when(sellerProductService.list(eq("seller123"), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(p)));

        mockMvc.perform(get("/api/v1/seller/products")
                        .with(user(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Seller listings retrieved"))
                .andExpect(jsonPath("$.data.content[0].id").value("prod123"))
                .andExpect(jsonPath("$.data.content[0].sellerId").value("seller123"))
                .andExpect(jsonPath("$.data.content[0].totalReviews").value(10));
    }

    @Test
    void getProductById_ShouldReturnSellerProduct() throws Exception {
        CustomUserDetails seller = createMockSellerDetails("seller123");
        Product p = createSampleProduct("seller123", "prod123");

        when(sellerProductService.get("seller123", "prod123")).thenReturn(p);

        mockMvc.perform(get("/api/v1/seller/products/prod123")
                        .with(user(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Seller listing retrieved"))
                .andExpect(jsonPath("$.data.id").value("prod123"))
                .andExpect(jsonPath("$.data.title").value("Bio Crop Fertilizer"));
    }

    @Test
    void sellerCannotPerformPostOperations_ShouldBeMethodNotAllowed() throws Exception {
        CustomUserDetails seller = createMockSellerDetails("seller123");

        mockMvc.perform(post("/api/v1/seller/products")
                        .with(user(seller)))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void sellerCannotPerformPutOperations_ShouldBeMethodNotAllowed() throws Exception {
        CustomUserDetails seller = createMockSellerDetails("seller123");

        mockMvc.perform(put("/api/v1/seller/products/prod123")
                        .with(user(seller)))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void sellerCannotPerformDeleteOperations_ShouldBeMethodNotAllowed() throws Exception {
        CustomUserDetails seller = createMockSellerDetails("seller123");

        mockMvc.perform(delete("/api/v1/seller/products/prod123")
                        .with(user(seller)))
                .andExpect(status().isMethodNotAllowed());
    }
}
