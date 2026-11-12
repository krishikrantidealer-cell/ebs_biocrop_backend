package com.ebs.biocrop.controller;

import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductCollection;
import com.ebs.biocrop.entity.ProductImage;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.repository.ProductCollectionRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.service.SellerShopNameLookup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Implements Shiprocket / Fastrr Headless & Custom Website Catalog integration APIs
 * as per official Postman documentation:
 * https://documenter.getpostman.com/view/31751679/2sAYHxmPGf#aafc59ee-03ad-4e48-90db-6b656c313c3f
 *
 * NOTE: Fastrr checkout requires all IDs (products, variants, collections) to be returned
 * in standard numeric format (JSON Number / Long).
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
@Tag(name = "Fastrr Integration", description = "Endpoints for Shiprocket Fastrr 1-Click Checkout catalog ingestion and webhooks")
public class FastrrCatalogController {

    private static final Logger log = LoggerFactory.getLogger(FastrrCatalogController.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final ProductRepository productRepository;
    private final ProductCollectionRepository collectionRepository;
    private final GcsImageStorageService imageStorage;
    private final SellerShopNameLookup sellerNames;
    private final MongoTemplate mongoTemplate;

    public FastrrCatalogController(ProductRepository productRepository,
                                   ProductCollectionRepository collectionRepository,
                                   GcsImageStorageService imageStorage,
                                   SellerShopNameLookup sellerNames,
                                   MongoTemplate mongoTemplate) {
        this.productRepository = productRepository;
        this.collectionRepository = collectionRepository;
        this.imageStorage = imageStorage;
        this.sellerNames = sellerNames;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Converts any string ID (such as a MongoDB ObjectId hex string) into a
     * deterministic, positive numeric ID (Long) suitable for standard JSON numbers
     * and strictly compatible with Fastrr / Shopify requirements.
     */
    public static long toNumericId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return 0L;
        }
        if (rawId.matches("^\\d+$")) {
            try {
                return Long.parseLong(rawId);
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawId.getBytes(StandardCharsets.UTF_8));
            long val = ByteBuffer.wrap(digest, 0, 8).getLong() & 0x7FFFFFFFFFFFFFFFL;
            // Map into positive 13-digit range [1_000_000_000_000L, 8_999_999_999_999L] (fits safely in JavaScript Number.MAX_SAFE_INTEGER < 9*10^15)
            long min = 1_000_000_000_000L;
            long range = 8_000_000_000_000L;
            return min + (val % range);
        } catch (NoSuchAlgorithmException e) {
            long hash = Math.abs((long) rawId.hashCode());
            return 1_000_000_000_000L + (hash % 8_000_000_000_000L);
        }
    }

    /**
     * 1. GET /api/v1/fastrr/products?page=1&limit=12
     */
    @GetMapping("/fastrr/products")
    @Operation(summary = "Fastrr Fetch Products", description = "Returns active products in Shopify/Fastrr schema format with numeric IDs")
    public ResponseEntity<Map<String, Object>> getProducts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int limit) {

        int pageIndex = Math.max(0, page - 1);
        int pageSize = Math.min(Math.max(1, limit), 100);
        Pageable pageable = PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Criteria criteria = activeProductCriteria();
        Query query = Query.query(criteria).with(pageable);
        List<Product> products = mongoTemplate.find(query, Product.class);
        long total = mongoTemplate.count(Query.query(criteria), Product.class);

        Map<String, String> currentSellerNames = sellerNames.findCurrentShopNames(
                products.stream().map(Product::getSellerId).filter(Objects::nonNull).distinct().toList());

        List<Map<String, Object>> mappedProducts = products.stream()
                .map(p -> mapToFastrrProduct(p, currentSellerNames.get(p.getSellerId())))
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("products", mappedProducts);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * 2. GET /api/v1/fastrr/collections?page=1&limit=12
     */
    @GetMapping("/fastrr/collections")
    @Operation(summary = "Fastrr Fetch Collections", description = "Returns active collections in Fastrr schema format with numeric IDs")
    public ResponseEntity<Map<String, Object>> getCollections(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int limit) {

        List<ProductCollection> allCollections = collectionRepository.findByIsActiveTrueOrderByPriorityAscNameAsc();
        int total = allCollections.size();

        int fromIndex = Math.max(0, (page - 1) * limit);
        List<ProductCollection> paged = fromIndex >= total ? List.of()
                : allCollections.subList(fromIndex, Math.min(fromIndex + limit, total));

        List<Map<String, Object>> mappedCollections = paged.stream()
                .map(this::mapToFastrrCollection)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("collections", mappedCollections);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * 3. GET /api/v1/fastrr/collections/products?collection_id={id}&page=1&limit=12
     * Accepts numeric collection ID, MongoDB collection ID, slug, or name.
     */
    @GetMapping({"/fastrr/collections/products", "/fastrr/collections/{collectionId}/products"})
    @Operation(summary = "Fastrr Fetch Products by Collection", description = "Returns products belonging to a collection, matching numeric or slug ID")
    public ResponseEntity<Map<String, Object>> getProductsByCollection(
            @PathVariable(required = false) String collectionId,
            @RequestParam(name = "collection_id", required = false) String paramCollectionId,
            @RequestParam(name = "collectionId", required = false) String paramCollectionIdCamel,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int limit) {

        String targetCollectionId = collectionId != null && !collectionId.isBlank()
                ? collectionId
                : (paramCollectionId != null && !paramCollectionId.isBlank() ? paramCollectionId : paramCollectionIdCamel);

        int pageIndex = Math.max(0, page - 1);
        int pageSize = Math.min(Math.max(1, limit), 100);
        Pageable pageable = PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Criteria criteria = activeProductCriteria();
        if (targetCollectionId != null && !targetCollectionId.isBlank()) {
            final String lookupId = targetCollectionId.trim();

            List<ProductCollection> allCollections = collectionRepository.findByIsActiveTrueOrderByPriorityAscNameAsc();
            Optional<ProductCollection> colOpt = allCollections.stream()
                    .filter(c -> (c.getId() != null && c.getId().equalsIgnoreCase(lookupId))
                            || String.valueOf(toNumericId(c.getId())).equals(lookupId)
                            || (c.getSlug() != null && c.getSlug().equalsIgnoreCase(lookupId))
                            || (c.getName() != null && c.getName().equalsIgnoreCase(lookupId)))
                    .findFirst();

            if (colOpt.isEmpty()) {
                // Check sub-collections
                colOpt = allCollections.stream()
                        .filter(c -> c.getSubCollections() != null && c.getSubCollections().stream()
                                .anyMatch(sc -> (sc.getId() != null && sc.getId().toHexString().equalsIgnoreCase(lookupId))
                                        || (sc.getId() != null && String.valueOf(toNumericId(sc.getId().toHexString())).equals(lookupId))
                                        || (sc.getSlug() != null && sc.getSlug().equalsIgnoreCase(lookupId))
                                        || (sc.getName() != null && sc.getName().equalsIgnoreCase(lookupId))))
                        .findFirst();
            }

            if (colOpt.isEmpty()) {
                colOpt = collectionRepository.findById(lookupId);
                if (colOpt.isEmpty()) {
                    colOpt = collectionRepository.findBySlugIgnoreCaseAndIsActiveTrue(lookupId);
                }
            }

            Set<String> searchTokens = new HashSet<>();
            searchTokens.add(lookupId);
            if (colOpt.isPresent()) {
                ProductCollection col = colOpt.get();
                if (col.getName() != null) searchTokens.add(col.getName());
                if (col.getSlug() != null) searchTokens.add(col.getSlug());
                if (col.getId() != null) searchTokens.add(col.getId());
                if (col.getSubCollections() != null) {
                    col.getSubCollections().forEach(sc -> {
                        if (sc.getId() != null) searchTokens.add(sc.getId().toHexString());
                        if (sc.getName() != null) searchTokens.add(sc.getName());
                        if (sc.getSlug() != null) searchTokens.add(sc.getSlug());
                    });
                }
            }

            Criteria[] matches = searchTokens.stream()
                    .map(token -> Criteria.where("assignedCollections").regex("^" + Pattern.quote(token) + "$", "i"))
                    .toArray(Criteria[]::new);

            criteria = new Criteria().andOperator(criteria, new Criteria().orOperator(matches));
        }

        Query query = Query.query(criteria).with(pageable);
        List<Product> products = mongoTemplate.find(query, Product.class);
        long total = mongoTemplate.count(Query.query(criteria), Product.class);

        Map<String, String> currentSellerNames = sellerNames.findCurrentShopNames(
                products.stream().map(Product::getSellerId).filter(Objects::nonNull).distinct().toList());

        List<Map<String, Object>> mappedProducts = products.stream()
                .map(p -> mapToFastrrProduct(p, currentSellerNames.get(p.getSellerId())))
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("products", mappedProducts);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * Optional direct single-product fetch by numeric ID, MongoDB ID, or SKU.
     */
    @GetMapping({"/fastrr/products/{id}", "/fastrr/product/{id}", "/fastrr/product"})
    @Operation(summary = "Fastrr Fetch Single Product", description = "Returns a single product by numeric ID, MongoDB ID, or SKU")
    public ResponseEntity<Map<String, Object>> getProductById(
            @PathVariable(required = false) String id,
            @RequestParam(name = "id", required = false) String paramId,
            @RequestParam(name = "product_id", required = false) String paramProductId) {

        String lookupId = id != null && !id.isBlank() ? id : (paramId != null && !paramId.isBlank() ? paramId : paramProductId);
        if (lookupId == null || lookupId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Product ID is required"));
        }
        lookupId = lookupId.trim();

        Optional<Product> prodOpt = Optional.empty();
        if (lookupId.matches("^\\d+$")) {
            long targetNum = Long.parseLong(lookupId);
            Criteria criteria = activeProductCriteria();
            List<Product> candidates = mongoTemplate.find(Query.query(criteria), Product.class);
            prodOpt = candidates.stream()
                    .filter(p -> toNumericId(p.getId()) == targetNum
                            || (p.getVariants() != null && p.getVariants().stream().anyMatch(v -> toNumericId(v.getId() != null ? v.getId() : p.getId() + "-v1") == targetNum)))
                    .findFirst();
        }
        if (prodOpt.isEmpty()) {
            prodOpt = productRepository.findById(lookupId);
        }
        if (prodOpt.isEmpty()) {
            prodOpt = productRepository.findBySkuIgnoreCase(lookupId);
        }
        if (prodOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Product p = prodOpt.get();
        Map<String, String> currentSellerNames = sellerNames.findCurrentShopNames(
                p.getSellerId() != null ? List.of(p.getSellerId()) : List.of());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("product", mapToFastrrProduct(p, currentSellerNames.get(p.getSellerId())));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * 4. POST /api/v1/fastrr/order/webhook and /api/v1/shiprocket/webhook
     * Webhook triggered by Fastrr when checkout completes.
     */
    @PostMapping({"/fastrr/order/webhook", "/shiprocket/webhook", "/fastrr/webhook"})
    @Operation(summary = "Fastrr Order Webhook", description = "Receives completed orders from Fastrr 1-Click checkout")
    public ResponseEntity<Map<String, Object>> handleOrderWebhook(@RequestBody(required = false) Map<String, Object> payload) {
        log.info("[FASTRR-WEBHOOK] Received order webhook payload: {}", payload);

        String orderId = payload != null && payload.containsKey("order_id") ? String.valueOf(payload.get("order_id")) : "UNKNOWN";
        String status = payload != null && payload.containsKey("status") ? String.valueOf(payload.get("status")) : "SUCCESS";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Order webhook received and processed successfully");
        response.put("order_id", orderId);
        response.put("webhook_status", status);
        response.put("timestamp", Instant.now().toString());

        return ResponseEntity.ok(response);
    }

    /**
     * 5. Loyalty Points endpoints (optional Fastrr catalog endpoints)
     */
    @PostMapping("/fastrr/loyalty/points")
    public ResponseEntity<Map<String, Object>> fetchLoyaltyPoints(@RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mobile_number", payload != null ? payload.getOrDefault("mobile_number", "") : "");
        data.put("available_points", 0);
        data.put("applicable_points", 0);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("data", data);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/fastrr/loyalty/block-points")
    public ResponseEntity<Map<String, Object>> blockLoyaltyPoints(@RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available_points", 0);
        data.put("debited_points", 0);
        data.put("status", true);
        data.put("message", "Valid Customer Id");
        data.put("transaction_id", UUID.randomUUID().toString().replace("-", ""));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("data", data);
        return ResponseEntity.ok(res);
    }

    private Criteria activeProductCriteria() {
        return new Criteria().andOperator(
                Criteria.where("status").regex("^ACTIVE$", "i"),
                Criteria.where("isAvailable").is(true),
                new Criteria().orOperator(
                        Criteria.where("isDeleted").is(false),
                        Criteria.where("isDeleted").is(null),
                        Criteria.where("isDeleted").exists(false)
                )
        );
    }

    private Map<String, Object> mapToFastrrProduct(Product product, String currentSellerName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", toNumericId(product.getId()));
        m.put("title", product.getTitle() != null ? product.getTitle() : "Product " + product.getSku());

        String desc = product.getDescription() != null ? product.getDescription() : "";
        m.put("body_html", desc.startsWith("<") ? desc : "<p>" + desc + "</p>");
        m.put("vendor", currentSellerName != null && !currentSellerName.isBlank() ? currentSellerName : (product.getSellerName() != null ? product.getSellerName() : "EBS BioCrop"));
        m.put("product_type", product.getTechnicalName() != null ? product.getTechnicalName() : "Agricultural Input");

        String createdAtStr = formatTimestamp(product.getCreatedAt());
        String updatedAtStr = formatTimestamp(product.getUpdatedAt());
        m.put("created_at", createdAtStr);
        m.put("updated_at", updatedAtStr);

        String handle = product.getTitle() != null
                ? product.getTitle().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "")
                : (product.getSku() != null ? product.getSku().toLowerCase() : String.valueOf(toNumericId(product.getId())));
        m.put("handle", handle);

        String tags = (product.getTechnicalName() != null ? product.getTechnicalName() + ", " : "") +
                (product.getSellerName() != null ? product.getSellerName() : "EBS BioCrop");
        m.put("tags", tags);
        m.put("status", "active");

        String primaryImageUrl = null;
        String sellerFolder = product.getSellerId() != null && !product.getSellerId().isBlank()
                ? product.getSellerId()
                : "platform";
        if (product.getProductImages() != null && !product.getProductImages().isEmpty()) {
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
        if (primaryImageUrl != null) {
            imgObj.put("src", primaryImageUrl);
        } else {
            imgObj.put("src", "https://ebskrishidukaan.com/icons/Icon-192.png");
        }
        m.put("image", imgObj);

        List<Map<String, Object>> variants = new ArrayList<>();
        if (product.getVariants() != null && !product.getVariants().isEmpty()) {
            int vIndex = 1;
            for (ProductVariant v : product.getVariants()) {
                Map<String, Object> vm = new LinkedHashMap<>();
                String rawVarKey = v.getId() != null && !v.getId().isBlank()
                        ? v.getId()
                        : (product.getId() + "-v" + vIndex);
                vm.put("id", toNumericId(rawVarKey));
                vm.put("title", v.getLabel() != null ? v.getLabel() : (v.getPackSize() != null ? v.getPackSize() + " " + v.getPackSizeUnit() : "Standard"));
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
            vm.put("id", toNumericId(product.getId() + "-v1"));
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

    private Map<String, Object> mapToFastrrCollection(ProductCollection collection) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", toNumericId(collection.getId()));
        m.put("title", collection.getName() != null ? collection.getName() : "Collection");
        m.put("handle", collection.getSlug() != null ? collection.getSlug() : String.valueOf(toNumericId(collection.getId())));
        m.put("body_html", collection.getDescription() != null ? (collection.getDescription().startsWith("<") ? collection.getDescription() : "<p>" + collection.getDescription() + "</p>") : "");

        String createdAtStr = collection.getCreatedAt() != null
                ? ISO_FORMATTER.format(collection.getCreatedAt().atOffset(ZoneOffset.UTC))
                : ISO_FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC));
        String updatedAtStr = collection.getUpdatedAt() != null
                ? ISO_FORMATTER.format(collection.getUpdatedAt().atOffset(ZoneOffset.UTC))
                : createdAtStr;

        m.put("created_at", createdAtStr);
        m.put("updated_at", updatedAtStr);

        Map<String, Object> imgObj = new LinkedHashMap<>();
        if (collection.getImage() != null && !collection.getImage().isBlank()) {
            imgObj.put("src", collection.getImage());
        } else {
            imgObj.put("src", "https://ebskrishidukaan.com/icons/Icon-192.png");
        }
        m.put("image", imgObj);

        return m;
    }

    private String formatTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) {
            return ISO_FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC));
        }
        return ISO_FORMATTER.format(dateTime.atOffset(ZoneOffset.UTC));
    }
}
