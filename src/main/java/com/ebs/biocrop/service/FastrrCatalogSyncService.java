package com.ebs.biocrop.service;

import com.ebs.biocrop.controller.FastrrCatalogController;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductCollection;
import com.ebs.biocrop.entity.ProductImage;
import com.ebs.biocrop.entity.ProductVariant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps internal catalog entities to Shiprocket Fastrr's Shopify-compatible
 * schema and pushes real-time product/collection webhook updates.
 */
@Service
public class FastrrCatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(FastrrCatalogSyncService.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final String FALLBACK_IMAGE_URL = "https://ebskrishidukaan.com/icons/Icon-192.png";

    private final FastrrCheckoutService fastrrCheckoutService;
    private final GcsImageStorageService imageStorage;
    private final SellerShopNameLookup sellerNames;

    @Autowired
    public FastrrCatalogSyncService(
            FastrrCheckoutService fastrrCheckoutService,
            GcsImageStorageService imageStorage,
            SellerShopNameLookup sellerNames) {
        this.fastrrCheckoutService = fastrrCheckoutService;
        this.imageStorage = imageStorage;
        this.sellerNames = sellerNames;
    }

    public void syncProductAsync(Product product) {
        if (product == null || fastrrCheckoutService == null || !fastrrCheckoutService.isConfigured()) {
            return;
        }
        String sellerName = null;
        if (sellerNames != null && product.getSellerId() != null && !product.getSellerId().isBlank()) {
            sellerName = sellerNames.findCurrentShopNames(List.of(product.getSellerId())).get(product.getSellerId());
        }
        boolean active = "ACTIVE".equalsIgnoreCase(product.getStatus())
                && Boolean.TRUE.equals(product.getIsAvailable())
                && !Boolean.TRUE.equals(product.getIsDeleted());
        Map<String, Object> payload = toFastrrProductPayload(product, sellerName, imageStorage, active);
        fastrrCheckoutService.pushProductWebhookAsync(payload);
    }

    public void syncCollectionAsync(ProductCollection collection) {
        if (collection == null || fastrrCheckoutService == null || !fastrrCheckoutService.isConfigured()) {
            return;
        }
        Map<String, Object> payload = toFastrrCollectionPayload(collection, imageStorage);
        fastrrCheckoutService.pushCollectionWebhookAsync(payload);
    }

    public void syncCategoryAsync(Category category) {
        if (category == null || fastrrCheckoutService == null || !fastrrCheckoutService.isConfigured()) {
            return;
        }
        Map<String, Object> payload = toFastrrCategoryCollectionPayload(category, imageStorage);
        fastrrCheckoutService.pushCollectionWebhookAsync(payload);
    }

    public static Map<String, Object> toFastrrProductPayload(
            Product product,
            String currentSellerName,
            GcsImageStorageService imageStorage,
            boolean active) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", FastrrCatalogController.toFastrrProductId(product));
        m.put("title", product.getTitle() != null ? product.getTitle() : "Product " + product.getSku());

        String desc = product.getDescription() != null ? product.getDescription() : "";
        m.put("body_html", desc.startsWith("<") ? desc : "<p>" + desc + "</p>");
        m.put("vendor", currentSellerName != null && !currentSellerName.isBlank()
                ? currentSellerName
                : (product.getSellerName() != null ? product.getSellerName() : "EBS BioCrop"));
        m.put("product_type", product.getTechnicalName() != null ? product.getTechnicalName() : "Agricultural Input");

        String createdAtStr = formatTimestamp(product.getCreatedAt());
        String updatedAtStr = formatTimestamp(product.getUpdatedAt());
        m.put("created_at", createdAtStr);
        m.put("updated_at", updatedAtStr);

        String handle = product.getTitle() != null
                ? product.getTitle().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "")
                : (product.getSku() != null ? product.getSku().toLowerCase() : String.valueOf(FastrrCatalogController.toNumericId(product.getId())));
        m.put("handle", handle);

        String tags = (product.getTechnicalName() != null ? product.getTechnicalName() + ", " : "")
                + (product.getSellerName() != null ? product.getSellerName() : "EBS BioCrop");
        m.put("tags", tags);
        m.put("status", active ? "active" : "archived");

        String primaryImageUrl = null;
        String sellerFolder = product.getSellerId() != null && !product.getSellerId().isBlank()
                ? product.getSellerId()
                : "platform";
        if (imageStorage != null && product.getProductImages() != null && !product.getProductImages().isEmpty()) {
            ProductImage primaryImg = product.getProductImages().get(0);
            try {
                if (primaryImg.getMidObjectName() != null) {
                    primaryImageUrl = imageStorage.createReadUrl(sellerFolder, product.getId(), primaryImg.getMidObjectName());
                } else if (primaryImg.getLowObjectName() != null) {
                    primaryImageUrl = imageStorage.createReadUrl(sellerFolder, product.getId(), primaryImg.getLowObjectName());
                }
            } catch (Exception ex) {
                log.warn("[FASTRR] Failed to resolve image URL for product {}: {}", product.getId(), ex.getMessage());
            }
        }

        Map<String, Object> imgObj = new LinkedHashMap<>();
        imgObj.put("src", primaryImageUrl != null ? primaryImageUrl : FALLBACK_IMAGE_URL);
        m.put("image", imgObj);

        List<Map<String, Object>> variants = new ArrayList<>();
        if (product.getVariants() != null && !product.getVariants().isEmpty()) {
            int vIndex = 1;
            for (ProductVariant v : product.getVariants()) {
                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("id", FastrrCatalogController.toFastrrVariantId(product, v, vIndex));
                vm.put("title", v.getLabel() != null ? v.getLabel()
                        : (v.getPackSize() != null ? v.getPackSize() + " " + v.getUnit() : "Standard"));
                double price = v.getDisplayRate() != null ? v.getDisplayRate() : 0.0;
                vm.put("price", String.format(Locale.ROOT, "%.2f", price));
                vm.put("sku", v.getSku() != null ? v.getSku() : product.getSku());
                vm.put("created_at", createdAtStr);
                vm.put("updated_at", updatedAtStr);
                vm.put("taxable", true);

                int grams = v.getSwg() != null && v.getSwg() > 0 ? v.getSwg().intValue()
                        : (v.getPackSize() != null ? v.getPackSize() : 500);
                vm.put("grams", grams);
                vm.put("weight", grams / 1000.0);
                vm.put("weight_unit", "kg");
                vm.put("image", imgObj);
                variants.add(vm);
                vIndex++;
            }
        } else {
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("id", FastrrCatalogController.toNumericId(product.getId() + "-v1"));
            vm.put("title", "Standard");
            vm.put("price", "0.00");
            vm.put("sku", product.getSku() != null ? product.getSku() : product.getId());
            vm.put("created_at", createdAtStr);
            vm.put("updated_at", updatedAtStr);
            vm.put("taxable", true);
            vm.put("grams", 500);
            vm.put("weight", 0.5);
            vm.put("weight_unit", "kg");
            vm.put("image", imgObj);
            variants.add(vm);
        }
        m.put("variants", variants);
        return m;
    }

    public static Map<String, Object> toFastrrCollectionPayload(
            ProductCollection collection,
            GcsImageStorageService imageStorage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", FastrrCatalogController.toNumericId(collection.getId()));
        m.put("title", collection.getName() != null ? collection.getName() : "Collection");
        m.put("handle", collection.getSlug() != null ? collection.getSlug()
                : String.valueOf(FastrrCatalogController.toNumericId(collection.getId())));
        m.put("body_html", collection.getDescription() != null
                ? (collection.getDescription().startsWith("<") ? collection.getDescription() : "<p>" + collection.getDescription() + "</p>")
                : "");

        String createdAtStr = collection.getCreatedAt() != null
                ? ISO_FORMATTER.format(collection.getCreatedAt().atOffset(ZoneOffset.UTC))
                : ISO_FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC));
        String updatedAtStr = collection.getUpdatedAt() != null
                ? ISO_FORMATTER.format(collection.getUpdatedAt().atOffset(ZoneOffset.UTC))
                : createdAtStr;

        m.put("created_at", createdAtStr);
        m.put("updated_at", updatedAtStr);

        Map<String, Object> imgObj = new LinkedHashMap<>();
        String imageUrl = null;
        if (imageStorage != null && collection.getImage() != null && !collection.getImage().isBlank()) {
            try {
                imageUrl = imageStorage.createCollectionImageReadUrl(
                        collection.getId() != null ? collection.getId() : "fastrr", collection.getImage());
            } catch (RuntimeException ex) {
                log.warn("[FASTRR] Failed to resolve collection image URL for {}: {}", collection.getId(), ex.getMessage());
            }
        }
        imgObj.put("src", imageUrl != null ? imageUrl : FALLBACK_IMAGE_URL);
        m.put("image", imgObj);
        return m;
    }

    public static Map<String, Object> toFastrrCategoryCollectionPayload(
            Category category,
            GcsImageStorageService imageStorage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", FastrrCatalogController.toNumericId(category.getId()));
        m.put("title", category.getName() != null ? category.getName() : "Category");
        m.put("handle", category.getSlug() != null ? category.getSlug()
                : String.valueOf(FastrrCatalogController.toNumericId(category.getId())));
        m.put("body_html", category.getBannerTitle() != null && !category.getBannerTitle().isBlank()
                ? "<p>" + category.getBannerTitle() + "</p>"
                : "");

        String createdAtStr = category.getCreatedAt() != null
                ? ISO_FORMATTER.format(category.getCreatedAt().atOffset(ZoneOffset.UTC))
                : ISO_FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC));
        String updatedAtStr = category.getUpdatedAt() != null
                ? ISO_FORMATTER.format(category.getUpdatedAt().atOffset(ZoneOffset.UTC))
                : createdAtStr;

        m.put("created_at", createdAtStr);
        m.put("updated_at", updatedAtStr);

        Map<String, Object> imgObj = new LinkedHashMap<>();
        String imageUrl = null;
        String rawImage = (category.getCategoryImageUri() != null && !category.getCategoryImageUri().isBlank())
                ? category.getCategoryImageUri()
                : category.getBannerImage();
        if (imageStorage != null && rawImage != null && !rawImage.isBlank()) {
            try {
                imageUrl = imageStorage.createCategoryImageReadUrl(
                        category.getId() != null ? category.getId() : "fastrr", rawImage);
            } catch (RuntimeException ex) {
                log.warn("[FASTRR] Failed to resolve category image URL for {}: {}", category.getId(), ex.getMessage());
            }
        }
        imgObj.put("src", imageUrl != null ? imageUrl : FALLBACK_IMAGE_URL);
        m.put("image", imgObj);
        return m;
    }

    private static String formatTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) {
            return ISO_FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC));
        }
        return ISO_FORMATTER.format(dateTime.atOffset(ZoneOffset.UTC));
    }
}
