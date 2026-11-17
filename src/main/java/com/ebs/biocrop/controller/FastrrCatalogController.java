package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductCollection;
import com.ebs.biocrop.entity.ProductImage;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.repository.ProductCollectionRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.service.FastrrCheckoutService;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.service.OrderLifecycleService;
import com.ebs.biocrop.service.SellerShopNameLookup;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
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
import java.util.regex.Matcher;

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
    private static final Pattern VARIANT_SKU_PATTERN = Pattern.compile("^S(\\d{4})-P(\\d{6})-V(\\d{2})$");
    private static final Pattern PRODUCT_SKU_PATTERN = Pattern.compile("^S(\\d{4})-P(\\d{6})$");

    private final ProductRepository productRepository;
    private final ProductCollectionRepository collectionRepository;
    private final GcsImageStorageService imageStorage;
    private final SellerShopNameLookup sellerNames;
    private final MongoTemplate mongoTemplate;

    @Value("${app.shiprocket.webhook-token:${SHIPROCKET_WEBHOOK_TOKEN:}}")
    private String shiprocketWebhookToken = "";

    @Autowired(required = false)
    private OrderLifecycleService orderLifecycle;

    @Autowired(required = false)
    private FastrrCheckoutService fastrrCheckoutService;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private CategoryRepository categoryRepository;

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
            long min = 1_000_000_000_000L;
            long range = 8_000_000_000_000L;
            return min + (val % range);
        } catch (NoSuchAlgorithmException e) {
            long hash = Math.abs((long) rawId.hashCode());
            return 1_000_000_000_000L + (hash % 8_000_000_000_000L);
        }
    }

    public static long toFastrrProductId(Product product) {
        String sku = product == null ? null : product.getSku();
        Matcher productMatch = sku == null ? null : PRODUCT_SKU_PATTERN.matcher(sku);
        if (productMatch != null && productMatch.matches()) {
            return Long.parseLong("1" + productMatch.group(1) + productMatch.group(2));
        }
        // Legacy products may only have a variant SKU; derive the product key from it.
        if (product != null && product.getVariants() != null) {
            for (ProductVariant variant : product.getVariants()) {
                Matcher variantMatch = variant == null || variant.getSku() == null
                        ? null : VARIANT_SKU_PATTERN.matcher(variant.getSku());
                if (variantMatch != null && variantMatch.matches()) {
                    return Long.parseLong("1" + variantMatch.group(1) + variantMatch.group(2));
                }
            }
        }
        return toNumericId(product == null ? null : product.getId());
    }

    public static long toFastrrVariantId(Product product, ProductVariant variant, int index) {
        String sku = variant == null ? null : variant.getSku();
        Matcher variantMatch = sku == null ? null : VARIANT_SKU_PATTERN.matcher(sku);
        if (variantMatch != null && variantMatch.matches()) {
            return Long.parseLong("2" + variantMatch.group(1) + variantMatch.group(2) + variantMatch.group(3));
        }
        String rawId = variant != null && variant.getId() != null && !variant.getId().isBlank()
                ? variant.getId() : (product.getId() + "-v" + index);
        return toNumericId(rawId);
    }

    /**
     * 1. GET /api/v1/fastrr/products?page=1&limit=12
     */
    @GetMapping("/fastrr/products")
    @Operation(summary = "Fastrr Fetch Products", description = "Returns active products in Shopify/Fastrr schema format with numeric IDs")
    public ResponseEntity<Map<String, Object>> getProducts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int limit) {

        int pageIndex = oneBasedPageIndex(page);
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
        List<Category> allCategories = categoryRepository != null
                ? categoryRepository.findByIsActiveTrueOrderBySortOrderAscNameAsc()
                : List.of();

        List<Map<String, Object>> combinedCollections = new ArrayList<>(allCollections.size() + allCategories.size());
        for (ProductCollection c : allCollections) {
            combinedCollections.add(mapToFastrrCollection(c));
        }
        for (Category cat : allCategories) {
            combinedCollections.add(mapToFastrrCategoryCollection(cat));
        }

        int total = combinedCollections.size();

        int pageSize = Math.min(Math.max(1, limit), 100);
        long requestedOffset = Math.max(0L, (long) Math.max(1, page) - 1L) * pageSize;
        int fromIndex = requestedOffset >= total ? total : (int) requestedOffset;
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<Map<String, Object>> mappedCollections = combinedCollections.subList(fromIndex, toIndex);

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

        int pageIndex = oneBasedPageIndex(page);
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

            List<Criteria> collectionOrCategoryMatches = new ArrayList<>(Arrays.asList(matches));

            // Also match active categories (and their descendants) when Fastrr queries a category collection
            if (colOpt.isEmpty() && categoryRepository != null) {
                List<Category> allCategories = categoryRepository.findByIsActiveTrueOrderBySortOrderAscNameAsc();
                Optional<Category> matchedCat = allCategories.stream()
                        .filter(cat -> (cat.getId() != null && cat.getId().equalsIgnoreCase(lookupId))
                                || String.valueOf(toNumericId(cat.getId())).equals(lookupId)
                                || (cat.getSlug() != null && cat.getSlug().equalsIgnoreCase(lookupId))
                                || (cat.getName() != null && cat.getName().equalsIgnoreCase(lookupId)))
                        .findFirst();
                if (matchedCat.isPresent()) {
                    Set<String> catIds = collectCategoryAndDescendantIds(matchedCat.get().getId(), allCategories);
                    if (!catIds.isEmpty()) {
                        collectionOrCategoryMatches.add(Criteria.where("categoryId").in(catIds));
                    }
                }
            }

            criteria = new Criteria().andOperator(criteria,
                    new Criteria().orOperator(collectionOrCategoryMatches.toArray(Criteria[]::new)));
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
            try {
                long targetNum = Long.parseLong(lookupId);
                Criteria criteria = activeProductCriteria();
                List<Product> candidates = mongoTemplate.find(Query.query(criteria), Product.class);
                prodOpt = candidates.stream()
                        .filter(p -> toFastrrProductId(p) == targetNum
                                || (p.getVariants() != null && java.util.stream.IntStream.range(0, p.getVariants().size()).anyMatch(index ->
                                toFastrrVariantId(p, p.getVariants().get(index), index + 1) == targetNum)))
                        .findFirst();
            } catch (NumberFormatException ignored) {
                // An oversized numeric lookup cannot be a Fastrr numeric ID.
            }
        }
        if (prodOpt.isEmpty()) {
            prodOpt = productRepository.findById(lookupId);
        }
        if (prodOpt.isEmpty()) {
            prodOpt = productRepository.findBySku(lookupId);
        }
        if (prodOpt.isEmpty()) {
            prodOpt = productRepository.findByProductCode(lookupId);
        }
        if (prodOpt.isEmpty() || !isFastrrVisible(prodOpt.get())) {
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
     * 3b. POST /api/v1/fastrr/checkout/token
     * Generates a server-signed Fastrr 1-Click Checkout access token for the given cart items.
     */
    @PostMapping("/fastrr/checkout/token")
    @Operation(summary = "Generate Fastrr Checkout Token", description = "Signs cart payload with HMAC-SHA256 and returns a Fastrr checkout access token")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createCheckoutToken(
            @RequestBody(required = false) Map<String, Object> request) {
        if (fastrrCheckoutService == null || !fastrrCheckoutService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Fastrr Checkout integration is not configured"));
        }
        if (request == null || !(request.get("items") instanceof List<?> rawItems) || rawItems.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("At least one item is required for Fastrr checkout"));
        }

        List<Map<String, Object>> fastrrItems = new ArrayList<>();
        for (Object itemObj : rawItems) {
            if (!(itemObj instanceof Map<?, ?> itemMap)) continue;
            String productId = itemMap.get("productId") != null ? String.valueOf(itemMap.get("productId")).trim() : "";
            String variantId = itemMap.get("variantId") != null ? String.valueOf(itemMap.get("variantId")).trim() : "";
            int quantity = 1;
            if (itemMap.get("quantity") instanceof Number n) {
                quantity = Math.max(1, n.intValue());
            }

            if (productId.isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Each item must include productId"));
            }
            Product product = productRepository.findById(productId).orElse(null);
            if (product == null || !isFastrrVisible(product)) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Product not available: " + productId));
            }

            long numericVariantId;
            List<ProductVariant> variants = product.getVariants();
            if (variants != null && !variants.isEmpty()) {
                int matchedIdx = 0;
                if (!variantId.isEmpty()) {
                    for (int i = 0; i < variants.size(); i++) {
                        if (variantId.equals(variants.get(i).getId())) {
                            matchedIdx = i;
                            break;
                        }
                    }
                }
                numericVariantId = toFastrrVariantId(product, variants.get(matchedIdx), matchedIdx);
            } else {
                numericVariantId = toFastrrProductId(product);
            }

            Map<String, Object> fastrrItem = new LinkedHashMap<>();
            fastrrItem.put("variant_id", String.valueOf(numericVariantId));
            fastrrItem.put("quantity", quantity);
            fastrrItems.add(fastrrItem);
        }

        if (fastrrItems.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("No valid items provided for Fastrr checkout"));
        }

        String redirectUrl = request.get("redirectUrl") != null ? String.valueOf(request.get("redirectUrl")) : null;
        Map<String, Object> tokenData = fastrrCheckoutService.createCheckoutToken(fastrrItems, redirectUrl);
        return ResponseEntity.ok(ApiResponse.ok("Fastrr checkout token generated", tokenData));
    }

    /**
     * 4. POST /api/v1/fastrr/order/webhook
     * Verifies HMAC-SHA256 signature and creates an idempotent Order in MongoDB.
     */
    @PostMapping({"/fastrr/order/webhook", "/fastrr/webhook"})
    @Operation(summary = "Fastrr Order Webhook", description = "Verifies HMAC-SHA256 signature and persists Fastrr orders")
    public ResponseEntity<Map<String, Object>> handleOrderWebhook(
            @RequestHeader(name = "X-Api-HMAC-SHA256", required = false) String hmacHeader,
            @RequestHeader(name = "X-Fastrr-Signature", required = false) String fastrrSig,
            HttpServletRequest httpRequest) {
        if (fastrrCheckoutService == null || !fastrrCheckoutService.isConfigured()) {
            log.info("[FASTRR-WEBHOOK] Webhook rejected: integration credentials are not configured");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "UNAVAILABLE");
            response.put("message", "Order webhook integration is not configured; no order was accepted");
            response.put("timestamp", Instant.now().toString());
            return ResponseEntity.status(503).body(response);
        }

        String rawBody;
        try {
            rawBody = new String(httpRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "BAD_REQUEST",
                    "message", "Unable to read Fastrr webhook request body",
                    "timestamp", Instant.now().toString()
            ));
        }

        String signature = (hmacHeader != null && !hmacHeader.isBlank()) ? hmacHeader : fastrrSig;
        if (rawBody.isBlank() || !fastrrCheckoutService.verifyWebhookSignature(rawBody, signature)) {
            log.warn("[FASTRR-WEBHOOK] Rejected webhook with missing or invalid HMAC signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", "UNAUTHORIZED",
                    "message", "Invalid or missing Fastrr webhook signature",
                    "timestamp", Instant.now().toString()
            ));
        }

        try {
            ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
            Map<String, Object> payload = mapper.readValue(rawBody, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> result = fastrrCheckoutService.processOrderWebhook(payload);
            result.put("timestamp", Instant.now().toString());
            return ResponseEntity.ok(result);
        } catch (com.ebs.biocrop.exception.AppException ex) {
            return ResponseEntity.status(ex.getStatus()).body(Map.of(
                    "status", "ERROR",
                    "message", ex.getMessage(),
                    "timestamp", Instant.now().toString()
            ));
        } catch (Exception ex) {
            log.error("[FASTRR-WEBHOOK] Failed to process order webhook: {}", ex.getMessage(), ex);
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "BAD_REQUEST",
                    "message", "Invalid Fastrr webhook payload: " + ex.getMessage(),
                    "timestamp", Instant.now().toString()
            ));
        }
    }

    /**
     * Dedicated Shiprocket courier tracking webhook.
     * Receives live tracking updates (AWB status scans) and updates shipment & carrier handover status.
     */
    @PostMapping({"/carrier/tracking/webhook", "/shiprocket/tracking/webhook", "/shiprocket/webhook"})
    @Operation(summary = "Carrier tracking webhook", description = "Shiprocket callback URL: /api/v1/carrier/tracking/webhook. Receives live AWB tracking scans.")
    public ResponseEntity<Map<String, Object>> handleShiprocketTrackingWebhook(
            @RequestHeader(name = "x-api-key", required = false) String apiKey,
            @RequestBody(required = false) Map<String, Object> payload) {
        if (shiprocketWebhookToken == null || shiprocketWebhookToken.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("status", "UNAVAILABLE", "message", "Shiprocket webhook token is not configured"));
        }
        if (apiKey == null || !java.security.MessageDigest.isEqual(
                shiprocketWebhookToken.getBytes(StandardCharsets.UTF_8),
                apiKey.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).body(Map.of("status", "UNAUTHORIZED", "message", "Invalid webhook token"));
        }
        if (payload == null || payload.get("awb") == null || String.valueOf(payload.get("awb")).isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "BAD_REQUEST", "message", "Missing awb in payload"));
        }
        String awb = String.valueOf(payload.get("awb")).trim();
        String currentStatus = String.valueOf(payload.getOrDefault("current_status", "")).trim().toUpperCase(Locale.ROOT);

        Query query = Query.query(Criteria.where("sellerFulfillments.shipments.trackingNumber").is(awb));
        Order order = mongoTemplate.findOne(query, Order.class);
        if (order == null) {
            return ResponseEntity.ok(Map.of("status", "IGNORED", "message", "No order found for AWB " + awb));
        }

        Object rawStatusId = payload.containsKey("current_status_id")
                ? payload.get("current_status_id") : payload.get("shipment_status_id");
        int statusId = rawStatusId instanceof Number number ? number.intValue() : parseStatusId(rawStatusId);
        if (currentStatus.isBlank()) currentStatus = String.valueOf(payload.getOrDefault("shipment_status", "")).trim().toUpperCase(Locale.ROOT);
        LocalDateTime eventTime = parseCarrierEventTime(payload);
        if (orderLifecycle == null) {
            return ResponseEntity.status(503).body(Map.of("status", "UNAVAILABLE", "message", "Order lifecycle service is unavailable"));
        }
        if (order.getSellerFulfillments() != null) {
            for (Order.SellerFulfillment f : order.getSellerFulfillments()) {
                boolean matchesAwb = f.getShipments() != null && f.getShipments().stream().anyMatch(s -> awb.equals(s.getTrackingNumber()));
                if (matchesAwb) {
                    orderLifecycle.recordCarrierTrackingStatus(order.getId(), f.getId(), awb, statusId, currentStatus, eventTime);
                    return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Shiprocket tracking scan recorded for AWB " + awb));
                }
            }
        }

        return ResponseEntity.ok(Map.of("status", "IGNORED", "message", "No matching fulfillment shipment found for AWB " + awb));
    }

    private int parseStatusId(Object value) {
        if (value == null) return -1;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private LocalDateTime parseCarrierEventTime(Map<String, Object> payload) {
        for (String field : List.of("current_timestamp", "date", "timestamp", "updated_at", "scan_date")) {
            Object value = payload.get(field);
            if (value == null || String.valueOf(value).isBlank()) continue;
            String text = String.valueOf(value).trim();
            try { return java.time.OffsetDateTime.parse(text).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime(); }
            catch (java.time.format.DateTimeParseException ignored) { }
            try { return LocalDateTime.parse(text.replace(' ', 'T')); }
            catch (java.time.format.DateTimeParseException ignored) { }
            try { return LocalDateTime.parse(text, java.time.format.DateTimeFormatter.ofPattern("dd MM yyyy HH:mm:ss", Locale.ROOT)); }
            catch (java.time.format.DateTimeParseException ignored) { }
        }
        return LocalDateTime.now();
    }

    /**
     * 5. Loyalty Points endpoints (Fastrr catalog endpoints)
     */
    @PostMapping("/fastrr/loyalty/points")
    @Operation(summary = "Fetch Loyalty Points", description = "Unavailable until the Fastrr loyalty contract and credentials are configured")
    public ResponseEntity<Map<String, Object>> fetchLoyaltyPoints(@RequestBody(required = false) Map<String, Object> payload) {
        return loyaltyIntegrationUnavailable();
    }

    @PostMapping("/fastrr/loyalty/block-points")
    @Operation(summary = "Block Loyalty Points", description = "Unavailable until the Fastrr loyalty contract and credentials are configured")
    public ResponseEntity<Map<String, Object>> blockLoyaltyPoints(@RequestBody(required = false) Map<String, Object> payload) {
        return loyaltyIntegrationUnavailable();
    }

    @PostMapping({"/fastrr/loyalty/unblock-points", "/fastrr/loyalty/unblockPoints"})
    @Operation(summary = "Unblock Loyalty Points", description = "Unavailable until the Fastrr loyalty contract and credentials are configured")
    public ResponseEntity<Map<String, Object>> unblockLoyaltyPoints(@RequestBody(required = false) Map<String, Object> payload) {
        return loyaltyIntegrationUnavailable();
    }

    private ResponseEntity<Map<String, Object>> loyaltyIntegrationUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", "UNAVAILABLE",
                "message", "Fastrr loyalty integration is not configured; no points were changed"));
    }

    private Criteria activeProductCriteria() {
        List<String> activeSellerIds = mongoTemplate.find(
                        Query.query(new Criteria().andOperator(
                                Criteria.where("role").is(UserRole.ROLE_SELLER),
                                Criteria.where("status").regex("^ACTIVE$", "i"),
                                Criteria.where("isBlocked").ne(true),
                                Criteria.where("isDeleted").ne(true))),
                        User.class)
                .stream()
                .map(User::getId)
                .filter(Objects::nonNull)
                .toList();

        Criteria sellerVisibility = new Criteria().orOperator(
                Criteria.where("sellerId").in(activeSellerIds),
                Criteria.where("sellerId").is(null),
                Criteria.where("sellerId").exists(false));
        return new Criteria().andOperator(
                Criteria.where("status").regex("^ACTIVE$", "i"),
                Criteria.where("isAvailable").is(true),
                new Criteria().orOperator(
                        Criteria.where("isDeleted").is(false),
                        Criteria.where("isDeleted").is(null),
                        Criteria.where("isDeleted").exists(false)
                ),
                sellerVisibility
        );
    }

    private int oneBasedPageIndex(int page) {
        return page <= 1 ? 0 : page - 1;
    }

    private boolean isFastrrVisible(Product product) {
        boolean productVisible = product != null
                && "ACTIVE".equalsIgnoreCase(product.getStatus())
                && Boolean.TRUE.equals(product.getIsAvailable())
                && !Boolean.TRUE.equals(product.getIsDeleted());
        if (!productVisible || product.getSellerId() == null || product.getSellerId().isBlank()) {
            return productVisible;
        }
        User seller = mongoTemplate.findById(product.getSellerId(), User.class);
        return seller != null && seller.getRole() == UserRole.ROLE_SELLER
                && "ACTIVE".equalsIgnoreCase(seller.getStatus())
                && !Boolean.TRUE.equals(seller.getIsBlocked())
                && !Boolean.TRUE.equals(seller.getIsDeleted());
    }

    private Map<String, Object> mapToFastrrProduct(Product product, String currentSellerName) {
        return com.ebs.biocrop.service.FastrrCatalogSyncService.toFastrrProductPayload(
                product, currentSellerName, imageStorage, true);
    }

    private Map<String, Object> mapToFastrrCollection(ProductCollection collection) {
        return com.ebs.biocrop.service.FastrrCatalogSyncService.toFastrrCollectionPayload(
                collection, imageStorage);
    }

    private Map<String, Object> mapToFastrrCategoryCollection(Category category) {
        return com.ebs.biocrop.service.FastrrCatalogSyncService.toFastrrCategoryCollectionPayload(
                category, imageStorage);
    }

    private static Set<String> collectCategoryAndDescendantIds(String rootId, List<Category> allCategories) {
        Set<String> ids = new LinkedHashSet<>();
        if (rootId == null || rootId.isBlank()) {
            return ids;
        }
        ids.add(rootId);
        boolean added = true;
        while (added) {
            added = false;
            for (Category cat : allCategories) {
                if (cat.getId() != null && cat.getParentId() != null
                        && ids.contains(cat.getParentId()) && ids.add(cat.getId())) {
                    added = true;
                }
            }
        }
        return ids;
    }

    private String formatTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) {
            return ISO_FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC));
        }
        return ISO_FORMATTER.format(dateTime.atOffset(ZoneOffset.UTC));
    }
}
