package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.controller.FastrrCatalogController;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.OrderAddressSnapshot;
import com.ebs.biocrop.entity.OrderCustomerSnapshot;
import com.ebs.biocrop.entity.OrderItem;
import com.ebs.biocrop.entity.OrderPaymentDetails;
import com.ebs.biocrop.entity.OrderPaymentTransaction;
import com.ebs.biocrop.entity.OrderSellerSnapshot;
import com.ebs.biocrop.entity.OrderStatusEvent;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.OrderStatus;
import com.ebs.biocrop.entity.enums.PaymentMethod;
import com.ebs.biocrop.entity.enums.PaymentStatus;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.service.FastrrCheckoutService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.websocket.WebSocketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class FastrrCheckoutServiceImpl implements FastrrCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(FastrrCheckoutServiceImpl.class);

    private final MongoTemplate mongoTemplate;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Autowired(required = false)
    private WebSocketService webSocketService;

    @Autowired(required = false)
    private RedisJsonCache redisCache;

    @Value("${app.fastrr.enabled:true}")
    private boolean enabled;

    @Value("${app.fastrr.base-url:https://checkout-api.shiprocket.com}")
    private String baseUrl;

    @Value("${app.fastrr.api-key:}")
    private String apiKey;

    @Value("${app.fastrr.secret-key:}")
    private String secretKey;

    @Value("${app.fastrr.store-url:https://ebskrishidukaan.com}")
    private String storeUrl;

    @Value("${app.fastrr.redirect-url:https://ebskrishidukaan.com}")
    private String redirectUrl;

    public FastrrCheckoutServiceImpl(
            MongoTemplate mongoTemplate,
            OrderRepository orderRepository,
            ObjectMapper objectMapper) {
        this.mongoTemplate = mongoTemplate;
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public boolean isConfigured() {
        return enabled
                && apiKey != null && !apiKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    @Override
    public String getApiKey() {
        return apiKey;
    }

    @Override
    public String getStoreUrl() {
        return storeUrl;
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
        if (!isConfigured() || rawBody == null || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        try {
            byte[] hmacBytes = computeHmacSha256(rawBody.getBytes(StandardCharsets.UTF_8));
            String expectedBase64 = Base64.getEncoder().encodeToString(hmacBytes);
            String expectedHex = toHex(hmacBytes);
            byte[] providedBytes = signatureHeader.trim().getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expectedBase64.getBytes(StandardCharsets.UTF_8), providedBytes)
                    || MessageDigest.isEqual(
                            expectedHex.getBytes(StandardCharsets.UTF_8),
                            signatureHeader.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log.warn("[FASTRR] HMAC verification failed: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> createCheckoutToken(List<Map<String, Object>> fastrrItems, String customRedirectUrl) {
        if (!isConfigured()) {
            throw new AppException("Fastrr Checkout integration is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (fastrrItems == null || fastrrItems.isEmpty()) {
            throw new AppException("At least one checkout item is required", HttpStatus.BAD_REQUEST);
        }

        String effectiveRedirect = (customRedirectUrl != null && !customRedirectUrl.isBlank())
                ? customRedirectUrl.trim()
                : redirectUrl;

        Map<String, Object> cartData = new LinkedHashMap<>();
        cartData.put("items", fastrrItems);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cart_data", cartData);
        body.put("redirect_url", effectiveRedirect);
        body.put("timestamp", Instant.now().toString());

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            String hmacBase64 = Base64.getEncoder().encodeToString(
                    computeHmacSha256(jsonBody.getBytes(StandardCharsets.UTF_8)));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Api-Key", apiKey.trim());
            headers.set("X-Api-HMAC-SHA256", hmacBase64);

            String endpoint = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                    + "/api/v1/access-token/checkout";

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    endpoint,
                    new HttpEntity<>(jsonBody, headers),
                    Map.class);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) {
                throw new AppException("Empty response from Fastrr Checkout API", HttpStatus.BAD_GATEWAY);
            }

            Object resultObj = responseBody.get("result");
            Map<String, Object> resultMap = resultObj instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : responseBody;

            Object token = resultMap.get("token");
            if (token == null || String.valueOf(token).isBlank()) {
                Object msg = responseBody.getOrDefault("message", "Unable to generate Fastrr checkout token");
                throw new AppException("Fastrr token generation failed: " + msg, HttpStatus.BAD_GATEWAY);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", String.valueOf(token));
            if (resultMap.get("order_id") != null) {
                out.put("fastrrOrderId", String.valueOf(resultMap.get("order_id")));
            }
            out.put("apiKey", apiKey.trim());
            out.put("storeUrl", storeUrl);
            return out;
        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("[FASTRR] Token generation error: {}", ex.getMessage(), ex);
            throw new AppException("Failed to initialize Fastrr Checkout session: " + ex.getMessage(),
                    HttpStatus.BAD_GATEWAY);
        }
    }

    @Override
    public void pushProductWebhookAsync(Map<String, Object> productPayload) {
        pushWebhookAsync("/wh/v1/custom/product", productPayload);
    }

    @Override
    public void pushCollectionWebhookAsync(Map<String, Object> collectionPayload) {
        pushWebhookAsync("/wh/v1/custom/collection", collectionPayload);
    }

    private void pushWebhookAsync(String path, Map<String, Object> payload) {
        if (!isConfigured() || payload == null || payload.isEmpty()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                String jsonBody = objectMapper.writeValueAsString(payload);
                String hmacBase64 = Base64.getEncoder().encodeToString(
                        computeHmacSha256(jsonBody.getBytes(StandardCharsets.UTF_8)));

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("X-Api-Key", apiKey.trim());
                headers.set("X-Api-HMAC-SHA256", hmacBase64);

                String endpoint = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + path;
                ResponseEntity<String> response = restTemplate.postForEntity(
                        endpoint,
                        new HttpEntity<>(jsonBody, headers),
                        String.class);
                log.info("[FASTRR] Pushed {} id={} status={}", path, payload.get("id"), response.getStatusCode().value());
            } catch (Exception ex) {
                log.warn("[FASTRR] Failed to push {} id={}: {}", path, payload.get("id"), ex.getMessage());
            }
        });
    }

    @Override
    public Map<String, Object> processOrderWebhook(Map<String, Object> payload) {
        if (!isConfigured()) {
            throw new AppException("Order webhook integration is not configured; no order was accepted",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (payload == null || payload.isEmpty()) {
            throw new AppException("Empty webhook payload", HttpStatus.BAD_REQUEST);
        }

        String fastrrOrderId = firstNonBlank(
                asString(payload.get("order_id")),
                asString(payload.get("fastrr_order_id")),
                asString(payload.get("id")));
        if (fastrrOrderId == null) {
            throw new AppException("Missing order_id in Fastrr webhook payload", HttpStatus.BAD_REQUEST);
        }

        String idempotencyKey = "fastrr:" + fastrrOrderId;
        Order existing = mongoTemplate.findOne(
                Query.query(new Criteria().orOperator(
                        Criteria.where("idempotencyKey").is(idempotencyKey),
                        Criteria.where("orderNumber").is("FASTRR-" + fastrrOrderId))),
                Order.class);
        if (existing != null) {
            Map<String, Object> dup = new LinkedHashMap<>();
            dup.put("status", "ALREADY_PROCESSED");
            dup.put("orderId", existing.getId());
            dup.put("orderNumber", existing.getOrderNumber());
            return dup;
        }

        List<Map<String, Object>> rawItems = extractItems(payload);
        if (rawItems.isEmpty()) {
            throw new AppException("Missing order items in Fastrr webhook payload", HttpStatus.BAD_REQUEST);
        }

        List<Product> catalog = mongoTemplate.find(
                Query.query(Criteria.where("isDeleted").ne(true)),
                Product.class);

        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        order.setOrderNumber("FASTRR-" + fastrrOrderId);
        order.setCheckoutGroupId("CHK-FASTRR-" + fastrrOrderId);
        order.setIdempotencyKey(idempotencyKey);

        Map<String, Object> shippingMap = asMap(payload.get("shipping_address"));
        Map<String, Object> customerMap = asMap(payload.get("customer_details"));
        String phone = firstNonBlank(
                asString(shippingMap.get("phone")),
                asString(customerMap.get("phone")),
                asString(payload.get("phone")),
                asString(payload.get("customer_phone")));
        String email = firstNonBlank(
                asString(shippingMap.get("email")),
                asString(customerMap.get("email")),
                asString(payload.get("email")));
        String customerName = firstNonBlank(
                asString(shippingMap.get("name")),
                joinNonBlank(asString(shippingMap.get("first_name")), asString(shippingMap.get("last_name"))),
                asString(customerMap.get("name")),
                asString(payload.get("customer_name")),
                "Fastrr Customer");

        User matchedCustomer = findCustomerByPhone(phone);
        if (matchedCustomer != null) {
            order.setCustomerId(matchedCustomer.getId());
        }

        OrderCustomerSnapshot customerSnapshot = new OrderCustomerSnapshot();
        customerSnapshot.setName(customerName);
        customerSnapshot.setPhoneNumber(phone);
        customerSnapshot.setEmail(email);
        order.setCustomerSnapshot(customerSnapshot);

        OrderAddressSnapshot address = buildAddressSnapshot(shippingMap, customerName, phone);
        order.setShippingAddress(address);
        order.setBillingAddress(address);

        String paymentTypeRaw = firstNonBlank(
                asString(payload.get("payment_type")),
                asString(payload.get("payment_method")),
                "PREPAID");
        boolean isCod = paymentTypeRaw.toUpperCase(Locale.ROOT).contains("COD")
                || paymentTypeRaw.toUpperCase(Locale.ROOT).contains("CASH");

        Map<String, Order.SellerFulfillment> fulfillmentsBySeller = new LinkedHashMap<>();
        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalList = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal orderTotal = BigDecimal.ZERO;
        int seq = 1;

        for (Map<String, Object> rawItem : rawItems) {
            ResolvedLine resolved = resolveCatalogLine(catalog, rawItem);
            int quantity = Math.max(1, parseInt(rawItem.get("quantity"), 1));

            Product product = resolved.product();
            ProductVariant variant = resolved.variant();
            String sellerKey = (product.getSellerId() != null && !product.getSellerId().isBlank())
                    ? product.getSellerId()
                    : "PLATFORM";

            Order.SellerFulfillment fulfillment = fulfillmentsBySeller.computeIfAbsent(
                    sellerKey,
                    id -> buildSellerFulfillment(id, OrderStatus.CONFIRMED.name()));

            BigDecimal listPrice = resolveMoney(variant != null ? variant.getPrintedMrp() : null,
                    null, parseDecimal(rawItem.get("price")));
            BigDecimal unitPrice = resolveMoney(variant != null ? variant.getDisplayRate() : null,
                    null, parseDecimal(rawItem.get("price")));
            if (listPrice.compareTo(unitPrice) < 0) {
                listPrice = unitPrice;
            }

            BigDecimal qtyBd = BigDecimal.valueOf(quantity);
            BigDecimal lineList = listPrice.multiply(qtyBd).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineTotal = unitPrice.multiply(qtyBd).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineDiscount = lineList.subtract(lineTotal).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

            OrderItem item = new OrderItem();
            item.setItemOrderId(order.getOrderNumber() + "-" + String.format(Locale.ROOT, "%03d", seq++));
            item.setSellerId("PLATFORM".equals(sellerKey) ? null : sellerKey);
            item.setSellerFulfillmentId(fulfillment.getId());
            item.setProductId(product.getId());
            item.setCategoryId(product.getCategoryId());
            item.setVariantId(variant != null ? variant.getId() : null);
            item.setProductCode(product.getProductCode());
            item.setProductSku(product.getSku());
            item.setVariantSku(variant != null ? variant.getSku() : product.getSku());
            item.setQuantity(quantity);
            item.setListPrice(listPrice);
            item.setUnitPrice(unitPrice);
            item.setDiscountAmount(lineDiscount);
            item.setLineTotal(lineTotal);
            item.setItemStatus(OrderStatus.CONFIRMED.name());

            Order.ProductSnapshot ps = new Order.ProductSnapshot();
            ps.setTitle(product.getTitle());
            ps.setVariantLabel(variant != null ? variant.getLabel() : product.getTitle());
            ps.setHsnCode(product.getHsnCode());
            if (product.getProductImages() != null && !product.getProductImages().isEmpty()) {
                ps.setImageObjectName(product.getProductImages().getFirst().getMidObjectName());
            }
            item.setProductSnapshot(ps);

            if (variant != null) {
                OrderItem.VariantSnapshot vs = new OrderItem.VariantSnapshot();
                vs.setLabel(variant.getLabel());
                vs.setUnit(variant.getUnit());
                vs.setPackSize(variant.getPackSize());
                vs.setPackSizeUnit(variant.getPackSizeUnit());
                vs.setPackQuantity(variant.getPackQuantity());
                item.setVariantSnapshot(vs);
            }

            fulfillment.getItemOrderIds().add(item.getItemOrderId());
            orderItems.add(item);

            totalList = totalList.add(lineList);
            totalDiscount = totalDiscount.add(lineDiscount);
            orderTotal = orderTotal.add(lineTotal);

            decrementStock(product, variant, quantity);
        }

        order.setItems(orderItems);
        order.setSellerFulfillments(new ArrayList<>(fulfillmentsBySeller.values()));
        order.setStockReserved(true);

        Order.FinancialSummary summary = new Order.FinancialSummary();
        summary.setGrossMerchandiseValue(totalList.setScale(2, RoundingMode.HALF_UP));
        summary.setDiscountTotal(totalDiscount.setScale(2, RoundingMode.HALF_UP));
        summary.setOriginalOrderTotal(orderTotal.setScale(2, RoundingMode.HALF_UP));
        summary.setCurrentOrderTotal(orderTotal.setScale(2, RoundingMode.HALF_UP));
        if (isCod) {
            summary.setOnlineAmountCaptured(BigDecimal.ZERO);
            summary.setCodDue(orderTotal.setScale(2, RoundingMode.HALF_UP));
        } else {
            summary.setOnlineAmountCaptured(orderTotal.setScale(2, RoundingMode.HALF_UP));
            summary.setNetOnlineAmountRetained(orderTotal.setScale(2, RoundingMode.HALF_UP));
            summary.setCodDue(BigDecimal.ZERO);
        }
        order.setFinancialSummary(summary);

        OrderPaymentDetails payment = new OrderPaymentDetails();
        payment.setProvider("FASTRR");
        payment.setProviderOrderId(fastrrOrderId);
        payment.setPaymentType(isCod ? PaymentMethod.COD.name() : PaymentMethod.PREPAID.name());
        payment.setStatus(isCod ? PaymentStatus.CREATED.name() : PaymentStatus.CAPTURED.name());
        payment.setAmountPaid(isCod ? BigDecimal.ZERO : orderTotal.setScale(2, RoundingMode.HALF_UP));
        payment.setCodDue(isCod ? orderTotal.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        payment.setRemainingAmount(isCod ? orderTotal.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);

        OrderPaymentTransaction txn = new OrderPaymentTransaction();
        txn.setTransactionId(firstNonBlank(
                asString(payload.get("fastrr_transaction_id")),
                asString(payload.get("transaction_id")),
                "FASTRR-TXN-" + fastrrOrderId));
        txn.setType(isCod ? "COD_ORDER" : "CAPTURE");
        txn.setAmount(orderTotal.setScale(2, RoundingMode.HALF_UP));
        txn.setStatus(isCod ? PaymentStatus.CREATED.name() : PaymentStatus.CAPTURED.name());
        txn.setProvider("FASTRR");
        txn.setProviderPaymentId(fastrrOrderId);
        txn.setOccurredAt(now);
        payment.setTransactions(List.of(txn));
        order.setPayment(payment);

        String overallStatus = OrderStatus.CONFIRMED.name();
        order.setOverallStatus(overallStatus);
        order.setPlacedAt(now);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);

        OrderStatusEvent event = new OrderStatusEvent();
        event.setStatus(overallStatus);
        event.setOccurredAt(now);
        event.setActor("FASTRR_WEBHOOK");
        event.setNote("Order created via Fastrr 1-Click Checkout (" + fastrrOrderId + ")");
        order.setStatusHistory(List.of(event));

        Order saved = orderRepository.save(order);
        if (redisCache != null) {
            redisCache.invalidateRegion("public-products");
        }
        if (webSocketService != null) {
            webSocketService.broadcast("orders:created", Map.of(
                    "orderId", saved.getId(),
                    "orderNumber", saved.getOrderNumber(),
                    "status", saved.getOverallStatus()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("orderId", saved.getId());
        result.put("orderNumber", saved.getOrderNumber());
        return result;
    }

    private Order.SellerFulfillment buildSellerFulfillment(String sellerKey, String status) {
        Order.SellerFulfillment group = new Order.SellerFulfillment();
        group.setId(new ObjectId().toHexString());
        if (!"PLATFORM".equals(sellerKey)) {
            group.setSellerId(sellerKey);
            User seller = mongoTemplate.findById(sellerKey, User.class);
            if (seller != null) {
                OrderSellerSnapshot snap = new OrderSellerSnapshot();
                snap.setTradeName(seller.getShopName());
                snap.setGstNumber(seller.getGstNumber());
                snap.setContactPhone(seller.getPhoneNumber());
                snap.setSellerCode(seller.getSellerCode());
                if (seller.getAddress() != null) {
                    snap.setDispatchPincode(seller.getAddress().getPincode());
                }
                group.setSellerSnapshot(snap);
            }
        }
        group.setStatus(status);
        group.setShipments(new ArrayList<>());
        return group;
    }

    private void decrementStock(Product product, ProductVariant variant, int quantity) {
        if (product.getId() == null || variant == null || variant.getId() == null || product.getVariants() == null) {
            return;
        }
        Query q = Query.query(Criteria.where("_id").is(product.getId()));
        Update update = new Update();
        for (int i = 0; i < product.getVariants().size(); i++) {
            if (variant.getId().equals(product.getVariants().get(i).getId())) {
                int vStock = variant.getStock() == null ? 0 : variant.getStock();
                update.set("variants." + i + ".stock", Math.max(0, vStock - quantity));
                mongoTemplate.updateFirst(q, update, Product.class);
                break;
            }
        }
    }

    private ResolvedLine resolveCatalogLine(List<Product> catalog, Map<String, Object> rawItem) {
        String variantIdStr = firstNonBlank(asString(rawItem.get("variant_id")), asString(rawItem.get("variantId")));
        String productIdStr = firstNonBlank(asString(rawItem.get("product_id")), asString(rawItem.get("productId")));
        String skuStr = asString(rawItem.get("sku"));

        for (Product p : catalog) {
            List<ProductVariant> variants = p.getVariants();
            if (variants != null && !variants.isEmpty()) {
                for (int i = 0; i < variants.size(); i++) {
                    ProductVariant v = variants.get(i);
                    String numericVarId = String.valueOf(FastrrCatalogController.toFastrrVariantId(p, v, i));
                    if (variantIdStr != null && (variantIdStr.equals(numericVarId) || variantIdStr.equals(v.getId()))) {
                        return new ResolvedLine(p, v);
                    }
                    if (skuStr != null && skuStr.equalsIgnoreCase(v.getSku())) {
                        return new ResolvedLine(p, v);
                    }
                }
            }
            String numericProdId = String.valueOf(FastrrCatalogController.toFastrrProductId(p));
            if (productIdStr != null && (productIdStr.equals(numericProdId) || productIdStr.equals(p.getId()))) {
                ProductVariant fallback = (variants != null && !variants.isEmpty()) ? variants.getFirst() : null;
                return new ResolvedLine(p, fallback);
            }
            if (skuStr != null && skuStr.equalsIgnoreCase(p.getSku())) {
                ProductVariant fallback = (variants != null && !variants.isEmpty()) ? variants.getFirst() : null;
                return new ResolvedLine(p, fallback);
            }
        }
        throw new AppException("Unknown product/variant in Fastrr order webhook: variant_id=" + variantIdStr,
                HttpStatus.BAD_REQUEST);
    }

    private User findCustomerByPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }
        if (digits.isBlank()) return null;
        return mongoTemplate.findOne(
                Query.query(Criteria.where("phoneNumber").regex(digits + "$")),
                User.class);
    }

    private OrderAddressSnapshot buildAddressSnapshot(Map<String, Object> shippingMap, String fallbackName, String fallbackPhone) {
        OrderAddressSnapshot snap = new OrderAddressSnapshot();
        snap.setName(firstNonBlank(asString(shippingMap.get("name")), fallbackName));
        snap.setPhoneNumber(firstNonBlank(asString(shippingMap.get("phone")), fallbackPhone));
        snap.setVillageArea(firstNonBlank(asString(shippingMap.get("address1")), asString(shippingMap.get("line1")), ""));
        snap.setAddress2(firstNonBlank(asString(shippingMap.get("address2")), asString(shippingMap.get("line2"))));
        snap.setCityTehsil(firstNonBlank(asString(shippingMap.get("city")), ""));
        snap.setDistrict(asString(shippingMap.get("district")));
        snap.setState(firstNonBlank(asString(shippingMap.get("state")), asString(shippingMap.get("province")), ""));
        snap.setPincode(firstNonBlank(asString(shippingMap.get("pincode")), asString(shippingMap.get("zip")), ""));
        snap.setCountry(firstNonBlank(asString(shippingMap.get("country")), "India"));
        return snap;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractItems(Map<String, Object> payload) {
        Map<String, Object> cartData = asMap(payload.get("cart_data"));
        Object itemsObj = cartData.containsKey("items") ? cartData.get("items")
                : (payload.containsKey("items") ? payload.get("items") : payload.get("line_items"));
        if (!(itemsObj instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private byte[] computeHmacSha256(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        return obj instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String asString(Object obj) {
        if (obj == null) return null;
        String s = String.valueOf(obj).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String joinNonBlank(String a, String b) {
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;
        return (a + " " + b).trim();
    }

    private static int parseInt(Object obj, int fallback) {
        if (obj instanceof Number n) return n.intValue();
        try {
            return obj == null ? fallback : Integer.parseInt(String.valueOf(obj).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static BigDecimal parseDecimal(Object obj) {
        if (obj instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return obj == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(obj).trim());
        } catch (Exception ex) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal resolveMoney(Double primary, Double secondary, BigDecimal fallback) {
        if (primary != null && primary > 0) return BigDecimal.valueOf(primary).setScale(2, RoundingMode.HALF_UP);
        if (secondary != null && secondary > 0) return BigDecimal.valueOf(secondary).setScale(2, RoundingMode.HALF_UP);
        return fallback != null && fallback.compareTo(BigDecimal.ZERO) > 0
                ? fallback.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    private record ResolvedLine(Product product, ProductVariant variant) { }
}
