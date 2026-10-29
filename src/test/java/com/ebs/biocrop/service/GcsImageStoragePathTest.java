package com.ebs.biocrop.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcsImageStoragePathTest {

    private static final String PRODUCT_ID = "6ad871a11df71c7f599dd935";
    private static final String ORIGINAL_SELLER_ID = "6ab4ada822033f1670582bda";

    @Test
    void acceptsStoredAssetPathAfterProductSellerChanges() {
        String objectName = "Sellers/" + ORIGINAL_SELLER_ID + "/products/" + PRODUCT_ID
                + "/original/image-id.jpg";

        assertTrue(GcsImageStorageService.belongsToProduct(PRODUCT_ID, objectName));
    }

    @Test
    void rejectsAnImagePathForAnotherProduct() {
        String objectName = "Sellers/" + ORIGINAL_SELLER_ID
                + "/products/another-product/original/image-id.jpg";

        assertFalse(GcsImageStorageService.belongsToProduct(PRODUCT_ID, objectName));
    }

    @Test
    void rejectsMalformedOrUnexpectedStoragePaths() {
        assertFalse(GcsImageStorageService.belongsToProduct(PRODUCT_ID, null));
        assertFalse(GcsImageStorageService.belongsToProduct(PRODUCT_ID,
                "Sellers/" + ORIGINAL_SELLER_ID + "/products/" + PRODUCT_ID + "/other/image-id.jpg"));
        assertFalse(GcsImageStorageService.belongsToProduct(PRODUCT_ID,
                "Categories/" + PRODUCT_ID + "/original/image-id.jpg"));
    }
}
