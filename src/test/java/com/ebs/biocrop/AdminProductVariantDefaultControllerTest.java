package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.ProductVariantDefaultRequest;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.AdminProductService;
import com.ebs.biocrop.service.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminProductVariantDefaultControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdminProductService adminProductService;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RateLimitService rateLimitService;

    private CustomUserDetails createAdminUser() {
        User u = new User();
        u.setId(new ObjectId().toHexString());
        u.setRole(UserRole.ROLE_ADMIN);
        return CustomUserDetails.build(u);
    }

    private CustomUserDetails createSellerUser() {
        User u = new User();
        u.setId(new ObjectId().toHexString());
        u.setRole(UserRole.ROLE_SELLER);
        return CustomUserDetails.build(u);
    }

    @Test
    @DisplayName("Admin can set variant to default via PATCH /default")
    void testAdminSetDefaultVariantSuccess() throws Exception {
        String productId = new ObjectId().toHexString();
        String variantId = new ObjectId().toHexString();

        Product product = new Product();
        product.setId(productId);
        product.setSku("PROD-101");
        String sellerId = new ObjectId().toHexString();
        product.setSellerId(sellerId);
        product.setSellerName("Outdated Snapshot");
        ProductVariant variant = new ProductVariant();
        variant.setId(variantId);
        variant.setLabel("1 Ltr");
        variant.setIsDefault(true);
        product.setVariants(List.of(variant));

        when(adminProductService.setDefaultVariant(eq(productId), eq(variantId), eq(true)))
                .thenReturn(product);
        User seller = new User();
        seller.setId(sellerId);
        seller.setRole(UserRole.ROLE_SELLER);
        seller.setShopName("Current Live Seller Shop");
        when(userRepository.findAllById(any())).thenReturn(List.of(seller));

        ProductVariantDefaultRequest req = new ProductVariantDefaultRequest(true);

        mockMvc.perform(patch("/api/v1/admin/products/{productId}/variants/{variantId}/default", productId, variantId)
                        .with(user(createAdminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Variant marked as default"))
                .andExpect(jsonPath("$.data.id").value(productId))
                .andExpect(jsonPath("$.data.sellerName").value("Current Live Seller Shop"))
                .andExpect(jsonPath("$.data.variants[0].isDefault").value(true));
    }

    @Test
    @DisplayName("Seller cannot call admin default variant endpoint (403 Forbidden)")
    void testSellerCannotCallAdminDefaultVariantEndpoint() throws Exception {
        String productId = new ObjectId().toHexString();
        String variantId = new ObjectId().toHexString();

        ProductVariantDefaultRequest req = new ProductVariantDefaultRequest(true);

        mockMvc.perform(patch("/api/v1/admin/products/{productId}/variants/{variantId}/default", productId, variantId)
                        .with(user(createSellerUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Request with missing isDefault field returns 400 Bad Request")
    void testMissingIsDefaultReturnsBadRequest() throws Exception {
        String productId = new ObjectId().toHexString();
        String variantId = new ObjectId().toHexString();

        mockMvc.perform(patch("/api/v1/admin/products/{productId}/variants/{variantId}/default", productId, variantId)
                        .with(user(createAdminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
