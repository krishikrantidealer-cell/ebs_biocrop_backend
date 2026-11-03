package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.service.GcsImageStorageService;

import java.util.List;

/** Shopper-facing product projection; excludes internal state and cost price. */
public record PublicProductResponse(
        String id,
        String sellerId,
        String sku,
        String productCode,
        String hsnCode,
        String title,
        String technicalName,
        String sellerName,
        String description,
        List<ProductImageUrls> productImages,
        String categoryId,
        List<String> assignedCollections,
        Boolean isAvailable,
        Boolean isFeatured,
        List<PublicVariant> variants,
        Double averageRating,
        Long totalReviews,
        String refundPolicy,
        Double productWeight,
        String productWeightUnit) {

    public static PublicProductResponse from(Product product, String currentSellerName,
                                             GcsImageStorageService imageStorage) {
        return new PublicProductResponse(
                product.getId(), product.getSellerId(), product.getSku(), product.getProductCode(), product.getHsnCode(), product.getTitle(), product.getTechnicalName(),
                currentSellerName, product.getDescription(),
                product.getProductImages() == null
                        ? List.of()
                        : product.getProductImages().stream()
                                .map(image -> new ProductImageUrls(
                                        image.getId(),
                                        image.getDisplayOrder(),
                                        imageStorage.createReadUrl(
                                                product.getSellerId(),
                                                product.getId(),
                                                image.getMidObjectName()),
                                        imageStorage.createReadUrl(
                                                product.getSellerId(),
                                                product.getId(),
                                                image.getLowObjectName())))
                                .toList(),
                product.getCategoryId(), product.getAssignedCollections(),
                product.getIsAvailable(), product.getIsFeatured(),
                product.getVariants() == null ? List.of() : product.getVariants().stream().map(PublicVariant::from).toList(),
                product.getAverageRating(), product.getTotalReviews() != null ? product.getTotalReviews() : 0L,
                product.getRefundPolicy(), product.getProductWeight(), product.getProductWeightUnit());
    }

    public record ProductImageUrls(
            String id,
            Integer displayOrder,
            String midUrl,
            String lowUrl) {
    }

    public record PublicVariant(
            String id,
            String sku,
            String label,
            String unit,
            Integer packSize,
            String packSizeUnit,
            Integer packQuantity,
            String shippedBy,
            Double swg,
            List<com.ebs.biocrop.entity.ProductDimensions> dimensions,
            Double displayRate,
            Double printedMrp,
            Double discountOnMrpRs,
            Double gstPercentage,
            Double discountPercentage,
            com.ebs.biocrop.entity.enums.PaymentMethod paymentMethod,
            Boolean isPrepaidAllowed,
            Boolean isPartialPaymentAllowed,
            Double minimumPartialPaymentPercentage,
            Double codCharge,
            Integer stock,
            Double pricePerBaseUnit,
            Integer displayOrder,
            Boolean isDefault) {
        public static PublicVariant from(ProductVariant variant) {
            return new PublicVariant(variant.getId(), variant.getSku(), variant.getLabel(), variant.getUnit(), variant.getPackSize(),
                    variant.getPackSizeUnit(), variant.getPackQuantity(),
                    variant.getShippedBy(), variant.getSwg(), variant.getDimensions(),
                    variant.getDisplayRate(), variant.getPrintedMrp(), variant.getDiscountOnMrpRs(),
                    variant.getGstPercentage(), variant.getDiscountPercentage(), variant.getPaymentMethod(),
                    variant.getIsPrepaidAllowed(), variant.getIsPartialPaymentAllowed(),
                    variant.getMinimumPartialPaymentPercentage(), variant.getCodCharge(), variant.getStock(),
                    variant.getPricePerBaseUnit(), variant.getDisplayOrder(), variant.getIsDefault());
        }
    }
}
