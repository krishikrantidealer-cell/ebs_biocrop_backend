package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.AdminProductVariantPatchRequest;
import com.ebs.biocrop.dto.request.AdminProductVariantWriteRequest;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.repository.ProductCollectionRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.service.CartService;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.service.ProductImageVariantService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.service.SellerProductService;
import com.ebs.biocrop.service.impl.AdminProductServiceImpl;
import com.ebs.biocrop.websocket.WebSocketService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminProductDefaultVariantTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductCollectionRepository collectionRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private GcsImageStorageService imageStorage;
    @Mock private ProductImageVariantService imageVariants;
    @Mock private RedisJsonCache redisCache;
    @Mock private SellerProductService sellerProductService;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private CartService cartService;
    @Mock private WebSocketService webSocketService;

    private AdminProductServiceImpl adminProductService;

    @BeforeEach
    void setUp() {
        adminProductService = new AdminProductServiceImpl(
                productRepository, collectionRepository, categoryRepository,
                imageStorage, imageVariants, redisCache, sellerProductService,
                mongoTemplate, cartService, webSocketService);
    }

    private Product createProductWithThreeVariants(String productId) {
        Product product = new Product();
        product.setId(productId);
        product.setSku("TEST-SKU-01");
        product.setStatus("ACTIVE");
        product.setIsAvailable(true);
        product.setIsDeleted(false);

        List<ProductVariant> variants = new ArrayList<>();

        ProductVariant v1 = new ProductVariant();
        v1.setId(new ObjectId().toHexString());
        v1.setLabel("1 Ltr");
        v1.setPackSize(1);
        v1.setPackSizeUnit("ltr");
        v1.setPackQuantity(1);
        v1.setDisplayRate(500.0);
        v1.setStock(50);
        v1.setDisplayOrder(1);
        v1.setIsDefault(true);
        variants.add(v1);

        ProductVariant v2 = new ProductVariant();
        v2.setId(new ObjectId().toHexString());
        v2.setLabel("2 Ltr");
        v2.setPackSize(2);
        v2.setPackSizeUnit("ltr");
        v2.setPackQuantity(1);
        v2.setDisplayRate(950.0);
        v2.setStock(40);
        v2.setDisplayOrder(2);
        v2.setIsDefault(false);
        variants.add(v2);

        ProductVariant v3 = new ProductVariant();
        v3.setId(new ObjectId().toHexString());
        v3.setLabel("5 Ltr");
        v3.setPackSize(5);
        v3.setPackSizeUnit("ltr");
        v3.setPackQuantity(1);
        v3.setDisplayRate(2200.0);
        v3.setStock(30);
        v3.setDisplayOrder(3);
        v3.setIsDefault(false);
        variants.add(v3);

        product.setVariants(variants);
        return product;
    }

    @Test
    @DisplayName("Setting variant 2 to default should mark variant 2 as true and all others as false")
    void testSetDefaultVariantToTrue() {
        String productId = new ObjectId().toHexString();
        Product product = createProductWithThreeVariants(productId);
        String v2Id = product.getVariants().get(1).getId();

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product updated = adminProductService.setDefaultVariant(productId, v2Id, true);

        assertNotNull(updated);
        ProductVariant v1 = updated.getVariants().get(0);
        ProductVariant v2 = updated.getVariants().get(1);
        ProductVariant v3 = updated.getVariants().get(2);

        assertFalse(v1.getIsDefault(), "Variant 1 must now be false");
        assertTrue(v2.getIsDefault(), "Variant 2 must now be true");
        assertFalse(v3.getIsDefault(), "Variant 3 must be false");
    }

    @Test
    @DisplayName("Setting variant 1 to false should unmark it without changing others")
    void testSetDefaultVariantToFalse() {
        String productId = new ObjectId().toHexString();
        Product product = createProductWithThreeVariants(productId);
        String v1Id = product.getVariants().get(0).getId();

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product updated = adminProductService.setDefaultVariant(productId, v1Id, false);

        ProductVariant v1 = updated.getVariants().get(0);
        ProductVariant v2 = updated.getVariants().get(1);
        ProductVariant v3 = updated.getVariants().get(2);

        assertFalse(v1.getIsDefault(), "Variant 1 must now be false");
        assertFalse(v2.getIsDefault(), "Variant 2 remains false");
        assertFalse(v3.getIsDefault(), "Variant 3 remains false");
    }

    @Test
    @DisplayName("Patching variant 3 with isDefault=true ensures all others become false")
    void testPatchVariantWithIsDefaultTrue() {
        String productId = new ObjectId().toHexString();
        Product product = createProductWithThreeVariants(productId);
        String v3Id = product.getVariants().get(2).getId();

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminProductVariantPatchRequest patch = new AdminProductVariantPatchRequest();
        patch.setIsDefault(true);

        Product updated = adminProductService.patchVariant(productId, v3Id, patch);

        assertEquals("ACTIVE", updated.getStatus(), "Product must remain ACTIVE after variant patch");
        assertTrue(updated.getIsAvailable(), "Product must remain available after variant patch");
        assertFalse(updated.getVariants().get(0).getIsDefault());
        assertFalse(updated.getVariants().get(1).getIsDefault());
        assertTrue(updated.getVariants().get(2).getIsDefault());
    }

    @Test
    @DisplayName("Deleting default variant promotes first remaining variant and demotes any multiple defaults")
    void testDeleteDefaultVariantWhenMultipleDefaultsExistRepairsToSingleDefault() {
        String productId = new ObjectId().toHexString();
        Product product = createProductWithThreeVariants(productId);
        // Corrupt legacy state: multiple variants set to default = true
        product.getVariants().get(0).setIsDefault(true);
        product.getVariants().get(1).setIsDefault(true);
        product.getVariants().get(2).setIsDefault(true);

        String v1Id = product.getVariants().get(0).getId();

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product updated = adminProductService.deleteVariant(productId, v1Id);

        assertEquals(2, updated.getVariants().size());
        assertEquals("ACTIVE", updated.getStatus(), "Product must remain ACTIVE after variant deletion");
        assertTrue(updated.getVariants().get(0).getIsDefault(), "Remaining first variant is promoted to true");
        assertFalse(updated.getVariants().get(1).getIsDefault(), "Remaining second variant is strictly demoted to false");
    }

    @Test
    @DisplayName("Deleting default variant 1 should automatically make next variant default")
    void testDeleteDefaultVariantPromotesNextVariant() {
        String productId = new ObjectId().toHexString();
        Product product = createProductWithThreeVariants(productId);
        String v1Id = product.getVariants().get(0).getId();

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product updated = adminProductService.deleteVariant(productId, v1Id);

        assertEquals(2, updated.getVariants().size());
        assertTrue(updated.getVariants().get(0).getIsDefault(), "Remaining first variant should now be default");
    }

    @Test
    @DisplayName("Adding a new variant with isDefault=true demotes all other variants to false")
    void testAddVariantWithDefaultTrue() {
        String productId = new ObjectId().toHexString();
        Product product = createProductWithThreeVariants(productId);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminProductVariantWriteRequest write = new AdminProductVariantWriteRequest();
        write.setLabel("10 Ltr");
        write.setPackSize(10);
        write.setPackSizeUnit("ltr");
        write.setPackQuantity(1);
        write.setUnit("ltr");
        write.setDisplayRate(4000.0);
        write.setStock(20);
        write.setIsDefault(true);

        Product updated = adminProductService.addVariant(productId, write);

        assertEquals(4, updated.getVariants().size());
        assertFalse(updated.getVariants().get(0).getIsDefault());
        assertFalse(updated.getVariants().get(1).getIsDefault());
        assertFalse(updated.getVariants().get(2).getIsDefault());
        assertTrue(updated.getVariants().get(3).getIsDefault(), "New variant must be default");
    }
}
