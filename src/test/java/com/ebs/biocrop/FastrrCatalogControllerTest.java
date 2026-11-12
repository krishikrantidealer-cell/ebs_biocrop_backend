package com.ebs.biocrop;

import com.ebs.biocrop.controller.FastrrCatalogController;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductCollection;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.repository.ProductCollectionRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.service.SellerShopNameLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FastrrCatalogControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductCollectionRepository collectionRepository;

    @Mock
    private GcsImageStorageService imageStorage;

    @Mock
    private SellerShopNameLookup sellerNames;

    @Mock
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        FastrrCatalogController controller = new FastrrCatalogController(
                productRepository, collectionRepository, imageStorage, sellerNames, mongoTemplate);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/fastrr/products returns valid Fastrr catalog structure")
    void testGetProducts() throws Exception {
        Product p = new Product();
        p.setId("prod_1");
        p.setTitle("Test Agri Product");
        p.setSku("SKU-001");
        p.setSellerId("seller_1");
        p.setDescription("Test description");

        ProductVariant v = new ProductVariant();
        v.setId("var_1");
        v.setLabel("1 Ltr");
        v.setDisplayRate(500.0);
        v.setSku("SKU-001-V1");
        p.setVariants(List.of(v));

        when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of(p));
        when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(1L);
        when(sellerNames.findCurrentShopNames(any())).thenReturn(Map.of("seller_1", "Organic Farm"));

        mockMvc.perform(get("/api/v1/fastrr/products?page=1&limit=12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.products[0].id").value("prod_1"))
                .andExpect(jsonPath("$.data.products[0].title").value("Test Agri Product"))
                .andExpect(jsonPath("$.data.products[0].variants[0].price").value("500.00"));
    }

    @Test
    @DisplayName("GET /api/v1/fastrr/collections returns valid Fastrr collections structure")
    void testGetCollections() throws Exception {
        ProductCollection col = new ProductCollection();
        col.setId("col_1");
        col.setName("Shop by Crop");
        col.setSlug("shop-by-crop");
        col.setDescription("Crop collections");

        when(collectionRepository.findByIsActiveTrueOrderByPriorityAscNameAsc()).thenReturn(List.of(col));

        mockMvc.perform(get("/api/v1/fastrr/collections?page=1&limit=12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.collections[0].id").value("col_1"))
                .andExpect(jsonPath("$.data.collections[0].title").value("Shop by Crop"));
    }

    @Test
    @DisplayName("POST /api/v1/fastrr/order/webhook succeeds with 200")
    void testOrderWebhook() throws Exception {
        String payload = """
                {
                    "order_id": "test_order_123",
                    "status": "SUCCESS",
                    "phone": "9999999999",
                    "total_amount_payable": 500.0
                }
                """;

        mockMvc.perform(post("/api/v1/fastrr/order/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.order_id").value("test_order_123"));
    }
}
