package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.AdminProductWriteRequest;
import com.ebs.biocrop.dto.request.SellerProductWriteRequest;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.AdminProductService;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.service.RateLimitService;
import com.ebs.biocrop.service.SellerShopNameLookup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminProductMultipartControllerTest {

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

    @MockitoBean
    private SellerShopNameLookup sellerShopNameLookup;

    @MockitoBean
    private GcsImageStorageService gcsImageStorageService;

    private CustomUserDetails createAdminUser() {
        User u = new User();
        u.setId(new ObjectId().toHexString());
        u.setRole(UserRole.ROLE_ADMIN);
        return CustomUserDetails.build(u);
    }

    private AdminProductWriteRequest validWriteRequest(String sellerId) {
        AdminProductWriteRequest req = new AdminProductWriteRequest();
        req.setSellerId(sellerId);
        req.setSku("PROD-MULTIPART-01");
        req.setTitle("Bio Crop Fertilizer");
        req.setCategoryId(new ObjectId().toHexString());
        req.setTechnicalName("Trichoderma viride");
        req.setDescription("Bio fertilizer description");
        req.setTechnicalContent("Active micro organisms 1.5%");
        SellerProductWriteRequest.VariantWriteRequest variant = new SellerProductWriteRequest.VariantWriteRequest();
        variant.setLabel("1 Liter");
        variant.setUnit("liter");
        variant.setPackSize(1);
        variant.setPackSizeUnit("liter");
        variant.setPackQuantity(1);
        variant.setPackUnit("bottle");
        variant.setDisplayRate(450.0);
        variant.setStock(100);
        req.setVariants(List.of(variant));
        return req;
    }

    @Test
    @DisplayName("Admin creates product via multipart form with JSON and images")
    void createProductMultipart_success() throws Exception {
        CustomUserDetails admin = createAdminUser();
        String sellerId = new ObjectId().toHexString();
        AdminProductWriteRequest writeRequest = validWriteRequest(sellerId);
        String productJson = objectMapper.writeValueAsString(writeRequest);

        Product created = new Product();
        created.setId("prod-12345");
        created.setSellerId(sellerId);
        created.setSku("PROD-MULTIPART-01");
        created.setTitle("Bio Crop Fertilizer");

        when(adminProductService.create(eq(admin.getId()), any(AdminProductWriteRequest.class), any()))
                .thenReturn(created);
        when(sellerShopNameLookup.findCurrentShopNames(any()))
                .thenReturn(Map.of(sellerId, "Organic Farm Supplies"));

        MockMultipartFile file1 = new MockMultipartFile("files", "image1.png", "image/png", new byte[]{1, 2, 3});
        MockMultipartFile file2 = new MockMultipartFile("files", "image2.png", "image/png", new byte[]{4, 5, 6});

        mockMvc.perform(multipart("/api/v1/admin/products")
                        .file(file1)
                        .file(file2)
                        .param("product", productJson)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .with(user(admin)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Product created"))
                .andExpect(jsonPath("$.data.id").value("prod-12345"))
                .andExpect(jsonPath("$.data.title").value("Bio Crop Fertilizer"));
    }

    @Test
    @DisplayName("Admin multipart creation rejects image URLs in JSON")
    void createProductMultipart_rejectsImageUrlsInJson() throws Exception {
        CustomUserDetails admin = createAdminUser();
        String sellerId = new ObjectId().toHexString();
        AdminProductWriteRequest writeRequest = validWriteRequest(sellerId);
        writeRequest.setImages(List.of("https://storage.googleapis.com/test.png"));
        String productJson = objectMapper.writeValueAsString(writeRequest);

        mockMvc.perform(multipart("/api/v1/admin/products")
                        .param("product", productJson)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .with(user(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Send uploaded image files as multipart parts; image URLs are not accepted during multipart creation"));
    }

    @Test
    @DisplayName("Admin multipart creation rejects when product payload is missing")
    void createProductMultipart_rejectsMissingProductPayload() throws Exception {
        CustomUserDetails admin = createAdminUser();
        MockMultipartFile file = new MockMultipartFile("files", "image.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/admin/products")
                        .file(file)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .with(user(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Product payload ('product' or 'data') is required"));
    }
}
