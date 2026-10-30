package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductDimensions;
import com.ebs.biocrop.entity.ProductImage;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.service.GcsImageStorageService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Management projection for admin and seller clients; omits persistence version metadata. */
public record ProductManagementResponse(
        String id, String sku, String productCode, String hsnCode, String sellerId,
        String title, String technicalName, String sellerName, String description,
        List<String> images, List<ManagementProductImage> productImages, String technicalContent, List<String> features, List<String> benefits,
        String modeOfAction, List<String> suitableCrops, List<String> targetPests,
        List<String> targetDiseases, String dosage, String applicationMethod, String categoryId,
        List<String> assignedCollections, ProductDimensions dimensions,
        Boolean isAvailable, Boolean isFeatured, List<ManagementVariant> variants,
        Double averageRating, Long totalReviews, String refundPolicy, Double productWeight, String productWeightUnit,
        String status, Boolean isDeleted, LocalDateTime deletedAt, LocalDateTime createdAt, LocalDateTime updatedAt,
        Integer version) {

    public static ProductManagementResponse from(Product product, String currentSellerName) {
        return from(product, currentSellerName, null);
    }

    public static ProductManagementResponse from(Product product, String currentSellerName, GcsImageStorageService imageStorage) {
        List<ManagementProductImage> managementImages = product.getProductImages() == null
                ? List.of()
                : product.getProductImages().stream()
                        .map(image -> ManagementProductImage.from(product, image, imageStorage))
                        .toList();

        List<String> finalImages = new ArrayList<>();
        if (product.getImages() != null && !product.getImages().isEmpty()) {
            finalImages.addAll(product.getImages());
        }
        if (finalImages.isEmpty() && !managementImages.isEmpty()) {
            for (ManagementProductImage img : managementImages) {
                if (img.midUrl() != null && !img.midUrl().isBlank()) {
                    finalImages.add(img.midUrl());
                } else if (img.lowUrl() != null && !img.lowUrl().isBlank()) {
                    finalImages.add(img.lowUrl());
                }
            }
        }

        return new ProductManagementResponse(product.getId(), product.getSku(), product.getProductCode(),
                product.getHsnCode(), product.getSellerId(), product.getTitle(), product.getTechnicalName(),
                currentSellerName, product.getDescription(), finalImages, managementImages, product.getTechnicalContent(),
                product.getFeatures(), product.getBenefits(), product.getModeOfAction(), product.getSuitableCrops(),
                product.getTargetPests(), product.getTargetDiseases(), product.getDosage(),
                product.getApplicationMethod(), product.getCategoryId(), product.getAssignedCollections(),
                product.getDimensions(), product.getIsAvailable(), product.getIsFeatured(),
                product.getVariants() == null ? List.of() : product.getVariants().stream().map(ManagementVariant::from).toList(),
                product.getAverageRating(), product.getTotalReviews() != null ? product.getTotalReviews() : 0L,
                product.getRefundPolicy(), product.getProductWeight(), product.getProductWeightUnit(),
                product.getStatus(), product.getIsDeleted(), product.getDeletedAt(), product.getCreatedAt(), product.getUpdatedAt(),
                product.getVersion());
    }

    public record ManagementProductImage(
            String id,
            Integer displayOrder,
            String originalObjectName,
            String midObjectName,
            String lowObjectName,
            String midUrl,
            String lowUrl) {
        public static ManagementProductImage from(Product product, ProductImage image, GcsImageStorageService imageStorage) {
            String midUrl = imageStorage != null && image.getMidObjectName() != null
                    ? imageStorage.createReadUrl(product.getSellerId(), product.getId(), image.getMidObjectName()) : null;
            String lowUrl = imageStorage != null && image.getLowObjectName() != null
                    ? imageStorage.createReadUrl(product.getSellerId(), product.getId(), image.getLowObjectName()) : null;
            return new ManagementProductImage(image.getId(), image.getDisplayOrder(),
                    image.getOriginalObjectName(), image.getMidObjectName(), image.getLowObjectName(),
                    midUrl, lowUrl);
        }
    }

    public record ManagementVariant(String id, String label, String unit, Integer packSize,
                                    String packSizeUnit, Integer packQuantity, String packUnit,
                                    String shippedBy, Double swg, Long totalBaseQuantity,
                                    String totalBaseUnit, ProductDimensions dimensions,
                                    Double displayRate, Double printedMrp, Double costPrice,
                                    Double discountRs, Double gstPercentage, Double discountPercentage,
                                    Integer stock, Double pricePerBaseUnit, Integer displayOrder,
                                    Boolean isDefault) {
        static ManagementVariant from(ProductVariant variant) {
            return new ManagementVariant(variant.getId(), variant.getLabel(), variant.getUnit(),
                    variant.getPackSize(), variant.getPackSizeUnit(), variant.getPackQuantity(),
                    variant.getPackUnit(), variant.getShippedBy(), variant.getSwg(),
                    variant.getTotalBaseQuantity(), variant.getTotalBaseUnit(), variant.getDimensions(),
                    variant.getDisplayRate(), variant.getPrintedMrp(), variant.getCostPrice(),
                    variant.getDiscountRs(), variant.getGstPercentage(), variant.getDiscountPercentage(),
                    variant.getStock(), variant.getPricePerBaseUnit(), variant.getDisplayOrder(), variant.getIsDefault());
        }
    }
}
