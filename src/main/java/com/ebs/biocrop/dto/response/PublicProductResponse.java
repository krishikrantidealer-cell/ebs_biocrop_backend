package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductDimensions;
import com.ebs.biocrop.entity.ProductVariant;

import java.util.List;

/** Shopper-facing product projection; excludes seller IDs, internal state, and cost price. */
public record PublicProductResponse(
        String id,
        String sku,
        String productCode,
        String hsnCode,
        String title,
        String technicalName,
        String vendor,
        String description,
        List<String> images,
        String technicalContent,
        List<String> features,
        List<String> benefits,
        String modeOfAction,
        List<String> suitableCrops,
        List<String> targetPests,
        List<String> targetDiseases,
        String dosage,
        String applicationMethod,
        String categoryId,
        List<String> collectionIds,
        List<String> subCollectionIds,
        ProductDimensions dimensions,
        Boolean isAvailable,
        Boolean isFeatured,
        List<PublicVariant> variants,
        Double ratings,
        String refundPolicy,
        Double productWeight,
        String productWeightUnit) {

    public static PublicProductResponse from(Product product) {
        return new PublicProductResponse(
                product.getId(), product.getSku(), product.getProductCode(), product.getHsnCode(), product.getTitle(), product.getTechnicalName(),
                product.getVendor(), product.getDescription(), product.getImages(), product.getTechnicalContent(),
                product.getFeatures(), product.getBenefits(), product.getModeOfAction(), product.getSuitableCrops(),
                product.getTargetPests(), product.getTargetDiseases(), product.getDosage(),
                product.getApplicationMethod(), product.getCategoryId(), product.getCollectionIds(),
                product.getSubCollectionIds(), product.getDimensions(), product.getIsAvailable(), product.getIsFeatured(),
                product.getVariants() == null ? List.of() : product.getVariants().stream().map(PublicVariant::from).toList(),
                product.getRatings(), product.getRefundPolicy(), product.getProductWeight(), product.getProductWeightUnit());
    }

    public record PublicVariant(
            String id,
            String label,
            String unit,
            Integer packSize,
            String packSizeUnit,
            Integer packQuantity,
            String packUnit,
            String shippedBy,
            Double swg,
            Long totalBaseQuantity,
            String totalBaseUnit,
            ProductDimensions dimensions,
            Double displayRate,
            Double printedMrp,
            Double discountRs,
            Double gstPercentage,
            Double discountPercentage,
            Integer stock,
            Double pricePerBaseUnit,
            Integer displayOrder,
            Boolean isDefault) {
        public static PublicVariant from(ProductVariant variant) {
            return new PublicVariant(variant.getId(), variant.getLabel(), variant.getUnit(), variant.getPackSize(),
                    variant.getPackSizeUnit(), variant.getPackQuantity(), variant.getPackUnit(),
                    variant.getShippedBy(), variant.getSwg(),
                    variant.getTotalBaseQuantity(), variant.getTotalBaseUnit(), variant.getDimensions(),
                    variant.getDisplayRate(), variant.getPrintedMrp(), variant.getDiscountRs(),
                    variant.getGstPercentage(), variant.getDiscountPercentage(), variant.getStock(),
                    variant.getPricePerBaseUnit(), variant.getDisplayOrder(), variant.getIsDefault());
        }
    }
}
