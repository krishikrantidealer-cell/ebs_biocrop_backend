package com.ebs.biocrop;

import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.dto.request.SellerProductWriteRequest;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.service.impl.SellerCodeService;
import com.ebs.biocrop.service.impl.SellerProductServiceImpl;
import com.ebs.biocrop.websocket.WebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SellerProductPricingTest {
    @Mock private ProductRepository products;
    @Mock private CategoryRepository categories;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private RedisJsonCache redisCache;
    @Mock private WebSocketService webSocketService;
    @Mock private SellerCodeService sellerCodeService;

    private SellerProductServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SellerProductServiceImpl(products, categories, mongoTemplate,
                redisCache, webSocketService, sellerCodeService);
        Category category = new Category();
        category.setId("category-1");
        category.setLevel(1);
        when(categories.findById("category-1")).thenReturn(Optional.of(category));
        when(categories.hasActiveChildren("category-1")).thenReturn(false);
    }

    @Test
    void derivesDiscountAmountAndPercentageFromMrpAndDisplayRate() {
        ProductVariant variant = variant(1000.0, 820.0);

        service.validateProduct(product(variant));

        assertEquals(180.0, variant.getDiscountOnMrpRs(), 0.0);
        assertEquals(18.0, variant.getDiscountPercentage(), 0.0);
    }

    @Test
    void rejectsDisplayRateAbovePrintedMrp() {
        ProductVariant variant = variant(1000.0, 1000.01);

        assertThrows(AppException.class, () -> service.validateProduct(product(variant)));
    }

    @Test
    void clearsDiscountValuesWhenPrintedMrpIsNotProvided() {
        ProductVariant variant = variant(null, 820.0);
        variant.setDiscountOnMrpRs(180.0);
        variant.setDiscountPercentage(18.0);

        service.validateProduct(product(variant));

        assertNull(variant.getDiscountOnMrpRs());
        assertNull(variant.getDiscountPercentage());
    }

    @Test
    void createsSellerlessPlatformProductWithoutUsingASequenceCollection() {
        SellerProductWriteRequest request = new SellerProductWriteRequest();
        request.setTitle("Platform product");
        request.setCategoryId("category-1");
        SellerProductWriteRequest.VariantWriteRequest variant = new SellerProductWriteRequest.VariantWriteRequest();
        variant.setUnit("l");
        variant.setPackSize(1);
        variant.setPackSizeUnit("l");
        variant.setPackQuantity(1);
        variant.setDisplayRate(100.0);
        variant.setPrintedMrp(100.0);
        variant.setStock(0);
        request.setVariants(List.of(variant));
        when(products.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product created = service.create(null, request);

        assertTrue(ObjectId.isValid(created.getId()));
        assertEquals("PRD-EBS-" + created.getId().toUpperCase(java.util.Locale.ROOT), created.getProductCode());
        assertEquals(created.getProductCode(), created.getSku());
        assertNull(created.getSellerId());
        assertEquals("ACTIVE", created.getStatus());
        assertEquals(Boolean.TRUE, created.getIsAvailable());
        assertEquals(Boolean.TRUE, created.getIsFeatured());
        verifyNoInteractions(mongoTemplate, sellerCodeService);
    }

    private Product product(ProductVariant variant) {
        Product product = new Product();
        product.setTitle("Test product");
        product.setCategoryId("category-1");
        product.setVariants(List.of(variant));
        return product;
    }

    private ProductVariant variant(Double mrp, Double displayRate) {
        ProductVariant variant = new ProductVariant();
        variant.setPrintedMrp(mrp);
        variant.setDisplayRate(displayRate);
        variant.setDiscountOnMrpRs(999.0);
        variant.setDiscountPercentage(99.0);
        return variant;
    }
}
