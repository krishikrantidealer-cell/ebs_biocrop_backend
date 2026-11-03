package com.ebs.biocrop;

import com.ebs.biocrop.dto.response.PublicProductResponse;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.dto.response.ProductManagementResponse;
import com.ebs.biocrop.service.GcsImageStorageService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class PublicProductResponseTest {
    @Test
    void responseUsesResolvedCurrentSellerNameInsteadOfProductSnapshot() {
        Product product = new Product();
        product.setId("product-id");
        product.setSellerId("seller-id");
        product.setSellerName("Stale Product Snapshot");

        PublicProductResponse response = PublicProductResponse.from(
                product, "Current Seller Shop", mock(GcsImageStorageService.class));

        assertEquals("Current Seller Shop", response.sellerName());
    }

    @Test
    void customerAndManagementResponsesExposeVariantPaymentThresholdInCorrectField() {
        Product product = new Product();
        product.setId("product-id");
        product.setSellerId("seller-id");
        ProductVariant variant = new ProductVariant();
        variant.setId("variant-id");
        variant.setMinimumPartialPaymentPercentage(25.0);
        variant.setCodCharge(45.0);
        product.setVariants(List.of(variant));

        PublicProductResponse.PublicVariant publicVariant = PublicProductResponse.from(
                product, "Shop", mock(GcsImageStorageService.class)).variants().get(0);
        ProductManagementResponse.ManagementVariant managementVariant = ProductManagementResponse
                .from(product, "Shop").variants().get(0);

        assertEquals(25.0, publicVariant.minimumPartialPaymentPercentage());
        assertEquals(45.0, publicVariant.codCharge());
        assertEquals(25.0, managementVariant.minimumPartialPaymentPercentage());
        assertEquals(45.0, managementVariant.codCharge());
    }
}
