package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.AdminProductUpdateRequest;
import com.ebs.biocrop.dto.request.SellerProductWriteRequest;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductDimensions;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminProductUpdateControllerTest {

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

    private Product mockProduct(String productId, String title) {
        Product p = new Product();
        p.setId(productId);
        p.setTitle(title);
        p.setSku("SKU-TEST-001");
        p.setStatus("INACTIVE");
        p.setIsAvailable(false);
        p.setIsFeatured(false);

        ProductVariant v = new ProductVariant();
        v.setId(new ObjectId().toHexString());
        v.setLabel("1 Liter");
        v.setUnit("l");
        v.setPackSize(1);
        v.setPackSizeUnit("l");
        v.setPackQuantity(1);
        v.setDisplayRate(500.0);
        v.setStock(50);
        v.setIsDefault(true);
        p.setVariants(List.of(v));
        return p;
    }

    @Test
    @DisplayName("Single update API via PATCH performs complete product update")
    void testCompleteProductUpdateViaPut() throws Exception {
        String productId = new ObjectId().toHexString();
        String sellerId = new ObjectId().toHexString();
        String categoryId = new ObjectId().toHexString();

        AdminProductUpdateRequest request = new AdminProductUpdateRequest();
        request.setSellerId(sellerId);
        request.setSku("SKU-COMPLETE-UPDATE");
        request.setTitle("Complete Updated Product");
        request.setTechnicalName("Tech Name Updated");
        request.setDescription("Full product description updated.");
        request.setCategoryId(categoryId);
        request.setProductWeight(1000.0);
        request.setProductWeightUnit("g");
        request.setDimensions(new ProductDimensions(20.0, 10.0, 10.0, "cm"));

        SellerProductWriteRequest.VariantWriteRequest variant = new SellerProductWriteRequest.VariantWriteRequest();
        variant.setLabel("1 Liter");
        variant.setUnit("l");
        variant.setPackSize(1);
        variant.setPackSizeUnit("l");
        variant.setPackQuantity(1);
        variant.setDisplayRate(850.0);
        variant.setStock(80);
        request.setVariants(List.of(variant));

        Product updatedProduct = mockProduct(productId, "Complete Updated Product");
        when(adminProductService.update(eq(productId), any(AdminProductUpdateRequest.class)))
                .thenReturn(updatedProduct);

        mockMvc.perform(patch("/api/v1/admin/products/{productId}", productId)
                        .with(user(createAdminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Product updated"))
                .andExpect(jsonPath("$.data.title").value("Complete Updated Product"));
    }

    @Test
    @DisplayName("Single update API via PATCH performs partial update of single field")
    void testPartialUpdateSingleFieldViaPatch() throws Exception {
        String productId = new ObjectId().toHexString();

        AdminProductUpdateRequest request = new AdminProductUpdateRequest();
        request.setTitle("Partially Patched Product Title");

        Product updatedProduct = mockProduct(productId, "Partially Patched Product Title");
        when(adminProductService.update(eq(productId), any(AdminProductUpdateRequest.class)))
                .thenReturn(updatedProduct);

        mockMvc.perform(patch("/api/v1/admin/products/{productId}", productId)
                        .with(user(createAdminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Product updated"))
                .andExpect(jsonPath("$.data.title").value("Partially Patched Product Title"));
    }

    @Test
    @DisplayName("Single update API via PATCH performs partial update of multiple fields")
    void testPartialUpdateMultipleFieldsViaPatch() throws Exception {
        String productId = new ObjectId().toHexString();

        AdminProductUpdateRequest request = new AdminProductUpdateRequest();
        request.setTitle("Multi-field Patched Title");
        request.setDescription("Only description and weight updated.");
        request.setProductWeight(750.0);

        Product updatedProduct = mockProduct(productId, "Multi-field Patched Title");
        when(adminProductService.update(eq(productId), any(AdminProductUpdateRequest.class)))
                .thenReturn(updatedProduct);

        mockMvc.perform(patch("/api/v1/admin/products/{productId}", productId)
                        .with(user(createAdminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Product updated"))
                .andExpect(jsonPath("$.data.title").value("Multi-field Patched Title"));
    }

    @Test
    @DisplayName("Seller cannot call admin product update endpoint (403 Forbidden)")
    void testSellerCannotCallAdminProductUpdateEndpoint() throws Exception {
        String productId = new ObjectId().toHexString();
        AdminProductUpdateRequest request = new AdminProductUpdateRequest();
        request.setTitle("Unauthorized Edit");

        mockMvc.perform(patch("/api/v1/admin/products/{productId}", productId)
                        .with(user(createSellerUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }
}
