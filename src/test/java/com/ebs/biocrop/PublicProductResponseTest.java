package com.ebs.biocrop;

import com.ebs.biocrop.dto.response.PublicProductResponse;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.service.GcsImageStorageService;
import org.junit.jupiter.api.Test;

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
}
