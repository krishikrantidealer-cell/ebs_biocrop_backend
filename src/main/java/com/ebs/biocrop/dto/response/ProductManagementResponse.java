package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductImage;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.service.GcsImageStorageService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Management projection for admin and seller clients; omits persistence version metadata. */
public record ProductManagementResponse(
        String id, String sku, String productCode, String hsnCode, Double gst, String sellerId,
        String title, String brandName, String technicalName, String sellerName, String description,
        List<ManagementProductImage> productImages, String categoryId,
        List<String> subCategoryIds,
        List<String> assignedCollections,
        Boolean isAvailable, Boolean isFeatured, Integer displayOrder,
        Map<String, Integer> categorySortOrders, Map<String, Integer> collectionSortOrders,
        Boolean isMarketplace, List<ManagementVariant> variants,
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

        return new ProductManagementResponse(product.getId(), product.getSku(), product.getProductCode(),
                product.getHsnCode(), product.getGst(), product.getSellerId(), product.getTitle(), product.getBrandName(), product.getTechnicalName(),
                currentSellerName, product.getDescription(), managementImages, product.getCategoryId(),
                product.getSubCategoryIds() == null ? List.of() : List.copyOf(product.getSubCategoryIds()),
                product.getAssignedCollections(),
                product.getIsAvailable(), product.getIsFeatured(),
                product.getDisplayOrder(), product.getCategorySortOrders(), product.getCollectionSortOrders(),
                product.getIsMarketplace() == null ? Boolean.TRUE : product.getIsMarketplace(),
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
            String sellerFolder = (product.getSellerId() != null && !product.getSellerId().isBlank())
                    ? product.getSellerId() : "platform";
            String midUrl = imageStorage != null && image.getMidObjectName() != null
                    ? imageStorage.createReadUrl(sellerFolder, product.getId(), image.getMidObjectName()) : null;
            String lowUrl = imageStorage != null && image.getLowObjectName() != null
                    ? imageStorage.createReadUrl(sellerFolder, product.getId(), image.getLowObjectName()) : null;
            return new ManagementProductImage(image.getId(), image.getDisplayOrder(),
                    image.getOriginalObjectName(), image.getMidObjectName(), image.getLowObjectName(),
                    midUrl, lowUrl);
        }
    }

    public record ManagementVariant(String id, String sku, String label, String unit, Integer packSize,
                                    Integer packQuantity,
                                    String shippedBy, String shippingThrough, Double swg, Double productWeight, String productWeightUnit, List<com.ebs.biocrop.entity.ProductDimensions> dimensions,
                                    Double displayRate, Double printedMrp,
                                    Double commissionPercentage, Double courierCharges,
                                    Double discountOnMrpRs, Double gstPercentage, Double discountPercentage,
                                    com.ebs.biocrop.entity.enums.PaymentMethod paymentMethod,
                                    Boolean isPrepaidAllowed, Boolean isPartialPaymentAllowed,
                                    Double minimumPartialPaymentPercentage, Double codCharge,
                                    Integer stock, Double pricePerBaseUnit, Integer displayOrder,
                                    Boolean isDefault) {
        static ManagementVariant from(ProductVariant variant) {
            return new ManagementVariant(variant.getId(), variant.getSku(), variant.getLabel(), variant.getUnit(),
                    variant.getPackSize(), variant.getPackQuantity(),
                    variant.getShippedBy(), variant.getShippingThrough(), variant.getSwg(), variant.getProductWeight(), variant.getProductWeightUnit(), variant.getDimensions(),
                    variant.getDisplayRate(), variant.getPrintedMrp(),
                    variant.getCommissionPercentage(), variant.getCourierCharges(),
                    variant.getDiscountOnMrpRs(), variant.getGstPercentage(), variant.getDiscountPercentage(),
                    variant.getPaymentMethod(), variant.getIsPrepaidAllowed(), variant.getIsPartialPaymentAllowed(),
                    variant.getMinimumPartialPaymentPercentage(), variant.getCodCharge(),
                    variant.getStock(), variant.getPricePerBaseUnit(), variant.getDisplayOrder(), variant.getIsDefault());
        }
    }
}
