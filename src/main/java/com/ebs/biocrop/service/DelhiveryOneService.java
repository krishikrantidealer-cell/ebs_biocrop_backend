package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.OrderAddressSnapshot;
import com.ebs.biocrop.entity.OrderItem;
import com.ebs.biocrop.entity.OrderStatusEvent;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.enums.OrderStatus;
import com.ebs.biocrop.entity.enums.PaymentMethod;
import com.ebs.biocrop.entity.enums.PaymentStatus;
import com.ebs.biocrop.entity.enums.ShipmentStatus;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.websocket.WebSocketService;
import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * End-to-end Order Fulfillment & Delhivery One Integration Service.
 * Enforces the canonical state machine:
 * PENDING -> CONFIRMED -> (Delhivery AWB + Label stored) -> PACKED (on label download)
 * -> SHIPPED -> IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED
 * and RTO_INITIATED -> RTO_IN_TRANSIT -> RTO_DELIVERED (with manual inventory restock).
 */
@Service
public class DelhiveryOneService {

    private static final Logger log = LoggerFactory.getLogger(DelhiveryOneService.class);

    private static final Set<String> ACTIVE_DELHIVERY_STATUSES = Set.of(
            OrderStatus.PACKED.name(),
            OrderStatus.SHIPPED.name(),
            OrderStatus.IN_TRANSIT.name(),
            OrderStatus.OUT_FOR_DELIVERY.name(),
            OrderStatus.RTO_INITIATED.name(),
            OrderStatus.RTO_IN_TRANSIT.name()
    );

    private final OrderRepository orderRepository;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final WebSocketService webSocketService;
    private final RedisJsonCache redisCache;

    private final boolean enabled;
    private final String baseUrl;
    private final String apiToken;
    private final String defaultPickupLocation;
    private final String webhookToken;

    @Autowired
    public DelhiveryOneService(
            OrderRepository orderRepository,
            MongoTemplate mongoTemplate,
            ObjectMapper objectMapper,
            @Autowired(required = false) WebSocketService webSocketService,
            @Autowired(required = false) RedisJsonCache redisCache,
            @Value("${delhivery.enabled:false}") boolean enabled,
            @Value("${delhivery.base-url:https://track.delhivery.com}") String baseUrl,
            @Value("${delhivery.api-token:}") String apiToken,
            @Value("${delhivery.pickup-location:}") String defaultPickupLocation,
            @Value("${delhivery.webhook-token:}") String webhookToken) {
        this(orderRepository, mongoTemplate, objectMapper, new RestTemplate(), webSocketService, redisCache,
                enabled, baseUrl, apiToken, defaultPickupLocation, webhookToken);
    }

    public DelhiveryOneService(
            OrderRepository orderRepository,
            MongoTemplate mongoTemplate,
            ObjectMapper objectMapper,
            RestTemplate restTemplate,
            WebSocketService webSocketService,
            RedisJsonCache redisCache,
            boolean enabled,
            String baseUrl,
            String apiToken,
            String defaultPickupLocation,
            String webhookToken) {
        this.orderRepository = orderRepository;
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.webSocketService = webSocketService;
        this.redisCache = redisCache;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "https://track.delhivery.com" : baseUrl.replaceAll("/+$", "");
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.defaultPickupLocation = defaultPickupLocation == null ? "" : defaultPickupLocation.trim();
        this.webhookToken = webhookToken == null ? "" : webhookToken.trim();
    }

    public boolean isConfigured() {
        return enabled && !apiToken.isBlank() && !defaultPickupLocation.isBlank();
    }

    /** Step 1: PENDING -> CONFIRMED (and automatically book Delhivery AWB + fetch label when configured). */
    public Order confirmOrder(String actorId, String orderId) {
        Order order = getOrder(orderId);
        String current = normalizeStatus(order.getOverallStatus());
        if (!OrderStatus.PENDING.name().equals(current) && !OrderStatus.PENDING_PAYMENT.name().equals(current)) {
            throw new AppException("Only PENDING orders can be confirmed (current: " + current + ")",
                    HttpStatus.CONFLICT);
        }

        LocalDateTime now = LocalDateTime.now();
        applyStatusToOrder(order, OrderStatus.CONFIRMED.name(), now, actorId,
                "Order confirmed and queued for Delhivery One shipment booking");
        Order saved = orderRepository.save(order);
        broadcastOrderUpdate(saved);

        if (isConfigured()) {
            try {
                return createShipmentAndLabel(actorId, saved.getId());
            } catch (Exception ex) {
                log.warn("[DELHIVERY] Order {} confirmed, but auto-fetching Delhivery label failed: {}",
                        saved.getOrderNumber(), ex.getMessage());
            }
        }
        return saved;
    }

    /**
     * Step 2: CONFIRMED -> Book shipment in Delhivery One & store shipping label reference.
     * Order remains CONFIRMED (with hasLabel=true) until the operator downloads the label.
     */
    public Order createShipmentAndLabel(String actorId, String orderId) {
        Order order = getOrder(orderId);
        String current = normalizeStatus(order.getOverallStatus());
        if (!OrderStatus.CONFIRMED.name().equals(current)) {
            throw new AppException("Order must be CONFIRMED before booking Delhivery One shipment (current: " + current + ")",
                    HttpStatus.CONFLICT);
        }

        Order.SellerFulfillment fulfillment = ensurePrimaryFulfillment(order);
        Order.Shipment existing = findPrimaryShipment(fulfillment);
        if (existing != null && hasText(existing.getTrackingNumber()) && hasText(existing.getLabelReference())) {
            return order; // Idempotent: AWB + label already stored
        }

        if (!isConfigured()) {
            throw new AppException(
                    "Delhivery One is not configured yet. Set DELHIVERY_ENABLED=true, DELHIVERY_API_TOKEN, and DELHIVERY_PICKUP_LOCATION.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        String waybill = lookupWaybillByOrderRef(order);
        if (!hasText(waybill)) {
            throw new AppException(
                    "Shipment is not booked in Delhivery One yet for order " + order.getOrderNumber()
                            + ". Dispatch team must book it in Delhivery One first.",
                    HttpStatus.NOT_FOUND);
        }
        String labelUrl = fetchPackingSlipUrl(waybill);

        LocalDateTime now = LocalDateTime.now();
        Order.Shipment shipment = existing != null ? existing : new Order.Shipment();
        if (!hasText(shipment.getShipmentId())) {
            shipment.setShipmentId(new ObjectId().toHexString());
        }
        shipment.setCarrier("DELHIVERY");
        shipment.setTrackingNumber(waybill);
        shipment.setBookingReference(waybill);
        shipment.setStatus(ShipmentStatus.MANIFESTED.name());
        shipment.setLabelReference(labelUrl);
        shipment.setLabelCreatedAt(now);
        shipment.setLastTrackingUpdateAt(now);
        shipment.setLastTrackingDescription("AWB " + waybill + " booked in Delhivery One; shipping label ready");
        shipment.setItemOrderIds(fulfillment.getItemOrderIds());
        if (existing == null) {
            fulfillment.getShipments().add(shipment);
        }

        OrderStatusEvent event = new OrderStatusEvent();
        event.setStatus(OrderStatus.CONFIRMED.name());
        event.setSellerFulfillmentId(fulfillment.getId());
        event.setOccurredAt(now);
        event.setActor(actorId);
        event.setNote("Delhivery One AWB generated (" + waybill + ") and shipping label stored");
        order.getStatusHistory().add(event);
        order.setUpdatedAt(now);

        Order saved = orderRepository.save(order);
        broadcastOrderUpdate(saved);
        return saved;
    }

    /**
     * Step 3: Download stored Delhivery label PDF & automatically transition CONFIRMED -> PACKED.
     * Strictly refuses if no label is stored in the backend yet.
     */
    public LabelDownloadResult downloadLabelAndMarkPacked(String actorId, String orderId) {
        Order order = getOrder(orderId);
        Order.SellerFulfillment fulfillment = ensurePrimaryFulfillment(order);
        Order.Shipment shipment = findPrimaryShipment(fulfillment);

        if (shipment == null || !hasText(shipment.getLabelReference())) {
            throw new AppException("Shipping label is not available in backend yet. Generate Delhivery label first.",
                    HttpStatus.NOT_FOUND);
        }

        LocalDateTime now = LocalDateTime.now();
        boolean transitionedToPacked = false;
        String current = normalizeStatus(order.getOverallStatus());
        if (OrderStatus.CONFIRMED.name().equals(current)) {
            shipment.setLabelDownloadedAt(now);
            shipment.setLabelDownloadedBy(actorId);
            shipment.setStatus(ShipmentStatus.MANIFESTED.name());
            applyStatusToOrder(order, OrderStatus.PACKED.name(), now, actorId,
                    "Shipping label downloaded (AWB: " + shipment.getTrackingNumber() + "); order marked PACKED");
            orderRepository.save(order);
            broadcastOrderUpdate(order);
            transitionedToPacked = true;
        } else if (shipment.getLabelDownloadedAt() == null) {
            shipment.setLabelDownloadedAt(now);
            shipment.setLabelDownloadedBy(actorId);
            order.setUpdatedAt(now);
            orderRepository.save(order);
        }

        byte[] pdfBytes = null;
        if (isConfigured() && hasText(shipment.getTrackingNumber())) {
            pdfBytes = tryFetchLabelPdfBytes(shipment.getTrackingNumber(), shipment.getLabelReference());
        }

        return new LabelDownloadResult(
                order.getId(),
                order.getOrderNumber(),
                shipment.getTrackingNumber(),
                shipment.getLabelReference(),
                pdfBytes,
                order.getOverallStatus(),
                transitionedToPacked
        );
    }

    /** Step 4: Manual or scheduled tracking sync with Delhivery One for one order. */
    public Order syncTrackingForOrder(String orderId) {
        Order order = getOrder(orderId);
        Order.SellerFulfillment fulfillment = ensurePrimaryFulfillment(order);
        Order.Shipment shipment = findPrimaryShipment(fulfillment);
        if (shipment == null || !hasText(shipment.getTrackingNumber())) {
            throw new AppException("No Delhivery tracking number (AWB) found on this order.", HttpStatus.BAD_REQUEST);
        }
        if (!isConfigured()) {
            throw new AppException("Delhivery One tracking sync is not configured.", HttpStatus.SERVICE_UNAVAILABLE);
        }

        String url = baseUrl + "/api/v1/packages/json/?waybill=" + shipment.getTrackingNumber();
        HttpEntity<Void> req = new HttpEntity<>(authHeaders());
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new AppException("Failed to fetch tracking from Delhivery One (status " + resp.getStatusCode().value() + ")",
                    HttpStatus.BAD_GATEWAY);
        }

        try {
            Map<String, Object> root = objectMapper.readValue(resp.getBody(), new TypeReference<>() {});
            Object sdObj = root.get("ShipmentData");
            if (sdObj instanceof List<?> sdList && !sdList.isEmpty() && sdList.getFirst() instanceof Map<?, ?> first) {
                Map<String, Object> shipMap = asMap(first.get("Shipment"));
                Map<String, Object> statusMap = asMap(shipMap.get("Status"));
                String statusName = asString(statusMap.get("Status"));
                String statusType = asString(statusMap.get("StatusType"));
                String location = asString(statusMap.get("StatusLocation"));
                String instructions = asString(statusMap.get("Instructions"));
                applyDelhiveryScan(order, fulfillment, shipment, statusName, statusType, location, instructions, "DELHIVERY_SYNC");
                Order saved = orderRepository.save(order);
                broadcastOrderUpdate(saved);
                return saved;
            }
            return order;
        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AppException("Unable to parse Delhivery tracking response: " + ex.getMessage(), HttpStatus.BAD_GATEWAY);
        }
    }

    /** Step 5: Real-time Delhivery One tracking & RTO webhook receiver. */
    public Map<String, Object> processTrackingWebhook(Map<String, Object> payload, String providedToken) {
        if (hasText(webhookToken)) {
            byte[] expectedBytes = webhookToken.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] providedBytes = providedToken == null
                    ? new byte[0]
                    : providedToken.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (!hasText(providedToken) || !java.security.MessageDigest.isEqual(expectedBytes, providedBytes)) {
                throw new AppException("Invalid Delhivery webhook token", HttpStatus.UNAUTHORIZED);
            }
        }
        if (payload == null || payload.isEmpty()) {
            throw new AppException("Empty Delhivery webhook payload", HttpStatus.BAD_REQUEST);
        }

        Map<String, Object> shipMap = payload.get("Shipment") instanceof Map<?, ?>
                ? asMap(payload.get("Shipment"))
                : payload;
        Map<String, Object> statusMap = shipMap.get("Status") instanceof Map<?, ?>
                ? asMap(shipMap.get("Status"))
                : shipMap;

        String waybill = firstNonBlank(
                asString(shipMap.get("AWB")),
                asString(shipMap.get("Waybill")),
                asString(shipMap.get("waybill")),
                asString(payload.get("waybill")),
                asString(payload.get("awb")));
        if (!hasText(waybill)) {
            throw new AppException("Missing waybill/AWB in Delhivery tracking webhook", HttpStatus.BAD_REQUEST);
        }

        Order order = mongoTemplate.findOne(
                Query.query(Criteria.where("sellerFulfillments.shipments.trackingNumber").is(waybill)),
                Order.class);
        if (order == null) {
            String refNo = firstNonBlank(
                    asString(shipMap.get("ReferenceNo")),
                    asString(shipMap.get("reference_no")),
                    asString(shipMap.get("OrderID")),
                    asString(payload.get("reference_no")),
                    asString(payload.get("order")));
            if (hasText(refNo)) {
                order = mongoTemplate.findOne(
                        Query.query(new Criteria().orOperator(
                                Criteria.where("orderNumber").is(refNo),
                                Criteria.where("orderNumber").is("FASTRR-" + refNo),
                                Criteria.where("checkoutGroupId").is(refNo))),
                        Order.class);
            }
        }
        if (order == null) {
            return Map.of("status", "IGNORED_UNKNOWN_WAYBILL", "waybill", waybill);
        }

        Order.SellerFulfillment fulfillment = ensurePrimaryFulfillment(order);
        Order.Shipment shipment = fulfillment.getShipments().stream()
                .filter(s -> waybill.equals(s.getTrackingNumber()))
                .findFirst()
                .orElse(findPrimaryShipment(fulfillment));
        if (shipment == null) {
            shipment = new Order.Shipment();
            shipment.setShipmentId(new ObjectId().toHexString());
            shipment.setCarrier("DELHIVERY");
            shipment.setTrackingNumber(waybill);
            shipment.setBookingReference(waybill);
            shipment.setItemOrderIds(fulfillment.getItemOrderIds());
            fulfillment.getShipments().add(shipment);
        } else if (!hasText(shipment.getTrackingNumber())) {
            shipment.setCarrier("DELHIVERY");
            shipment.setTrackingNumber(waybill);
        }
        if (!hasText(shipment.getLabelReference()) && isConfigured()) {
            shipment.setLabelReference(fetchPackingSlipUrl(waybill));
            shipment.setLabelCreatedAt(LocalDateTime.now());
        }

        String statusName = firstNonBlank(asString(statusMap.get("Status")), asString(payload.get("status")));
        String statusType = firstNonBlank(asString(statusMap.get("StatusType")), asString(payload.get("status_type")));
        String location = firstNonBlank(asString(statusMap.get("StatusLocation")), asString(payload.get("location")));
        String instructions = firstNonBlank(asString(statusMap.get("Instructions")), asString(payload.get("instructions")));

        applyDelhiveryScan(order, fulfillment, shipment, statusName, statusType, location, instructions, "DELHIVERY_WEBHOOK");
        Order saved = orderRepository.save(order);
        broadcastOrderUpdate(saved);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "PROCESSED");
        resp.put("orderId", saved.getId());
        resp.put("orderNumber", saved.getOrderNumber());
        resp.put("waybill", waybill);
        resp.put("overallStatus", saved.getOverallStatus());
        return resp;
    }

    /** Step 6: Manual inventory restock after RTO_DELIVERED inspection. */
    public Order restockRtoOrder(String actorId, String orderId) {
        Order order = getOrder(orderId);
        String current = normalizeStatus(order.getOverallStatus());
        if (!OrderStatus.RTO_DELIVERED.name().equals(current) && !OrderStatus.RETURNED.name().equals(current)) {
            throw new AppException("Order must be RTO_DELIVERED before restocking inventory (current: " + current + ")",
                    HttpStatus.CONFLICT);
        }

        Order.SellerFulfillment fulfillment = ensurePrimaryFulfillment(order);
        Order.Shipment shipment = findPrimaryShipment(fulfillment);
        if (shipment != null && Boolean.TRUE.equals(shipment.getRtoRestocked())) {
            return order; // Idempotent
        }

        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                if (hasText(item.getProductId()) && hasText(item.getVariantId())
                        && item.getQuantity() != null && item.getQuantity() > 0) {
                    Product restocked = mongoTemplate.findAndModify(
                            Query.query(Criteria.where("_id").is(item.getProductId())
                                    .and("variants._id").is(item.getVariantId())),
                            new Update()
                                    .inc("variants.$.stock", item.getQuantity())
                                    .set("isAvailable", true)
                                    .inc("version", 1),
                            org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
                            Product.class);
                    if (restocked != null && webSocketService != null) {
                        int newStock = restocked.getVariants() == null ? item.getQuantity()
                                : restocked.getVariants().stream()
                                        .filter(v -> item.getVariantId().equals(v.getId()))
                                        .map(v -> v.getStock() == null ? 0 : v.getStock())
                                        .findFirst()
                                        .orElse(item.getQuantity());
                        webSocketService.broadcast("inventory:stock_update", Map.of(
                                "productId", item.getProductId(),
                                "variantId", item.getVariantId(),
                                "stock", newStock));
                    }
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        if (shipment != null) {
            shipment.setRtoRestocked(true);
            shipment.setRtoRestockedAt(now);
        }
        OrderStatusEvent event = new OrderStatusEvent();
        event.setStatus(order.getOverallStatus());
        event.setSellerFulfillmentId(fulfillment.getId());
        event.setOccurredAt(now);
        event.setActor(actorId);
        event.setNote("RTO return inspected and inventory restocked");
        order.getStatusHistory().add(event);
        order.setUpdatedAt(now);

        Order saved = orderRepository.save(order);
        if (redisCache != null) {
            redisCache.invalidateRegion("public-products");
        }
        broadcastOrderUpdate(saved);
        if (webSocketService != null) {
            webSocketService.broadcast("products:update", Map.of("reason", "RTO_RESTOCK", "orderId", saved.getId()));
        }
        return saved;
    }

    /** 15-minute safety-net reconciliation: discovers newly booked labels for CONFIRMED orders and syncs active tracking. */
    @Scheduled(fixedDelayString = "${delhivery.poll-interval-ms:900000}")
    public void reconcileActiveShipments() {
        if (!isConfigured()) return;

        // 1. Discover AWBs & labels for CONFIRMED orders booked in Delhivery One by dispatch team
        List<Order> confirmedWithoutLabel = mongoTemplate.find(
                Query.query(Criteria.where("overallStatus").is(OrderStatus.CONFIRMED.name())
                        .and("sellerFulfillments.shipments.labelReference").exists(false)),
                Order.class);
        for (Order order : confirmedWithoutLabel) {
            try {
                createShipmentAndLabel("DELHIVERY_POLL", order.getId());
            } catch (Exception ignored) {
                // Not booked in Delhivery One by dispatch team yet
            }
        }

        // 2. Sync live tracking & RTO scans for active Delhivery shipments
        List<Order> activeOrders = mongoTemplate.find(
                Query.query(Criteria.where("overallStatus").in(ACTIVE_DELHIVERY_STATUSES)
                        .and("sellerFulfillments.shipments.carrier").is("DELHIVERY")),
                Order.class);
        for (Order order : activeOrders) {
            try {
                syncTrackingForOrder(order.getId());
            } catch (Exception ex) {
                log.warn("[DELHIVERY-POLL] Failed syncing order {}: {}", order.getOrderNumber(), ex.getMessage());
            }
        }
    }

    /** Looks up a shipment booked by the dispatch team in Delhivery One using order reference IDs (never creates a shipment). */
    private String lookupWaybillByOrderRef(Order order) {
        List<String> refs = new ArrayList<>();
        if (hasText(order.getOrderNumber())) {
            refs.add(order.getOrderNumber().trim());
            if (order.getOrderNumber().startsWith("FASTRR-")) {
                refs.add(order.getOrderNumber().substring("FASTRR-".length()));
            }
        }
        if (hasText(order.getCheckoutGroupId())) {
            refs.add(order.getCheckoutGroupId().trim());
        }
        if (refs.isEmpty()) return null;

        String refParam = String.join(",", refs.stream().distinct().toList());
        String url = baseUrl + "/api/v1/packages/json/?ref_ids=" + java.net.URLEncoder.encode(refParam, java.nio.charset.StandardCharsets.UTF_8);
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                return null;
            }
            Map<String, Object> parsed = objectMapper.readValue(resp.getBody(), new TypeReference<>() {});
            Object shipDataObj = parsed.get("ShipmentData");
            if (shipDataObj instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> first) {
                Map<String, Object> shipMap = asMap(first.get("Shipment"));
                return firstNonBlank(asString(shipMap.get("AWB")), asString(shipMap.get("Waybill")));
            }
        } catch (Exception ex) {
            log.warn("[DELHIVERY] ref_ids lookup failed for {}: {}", order.getOrderNumber(), ex.getMessage());
        }
        return null;
    }

    /** Maps Delhivery One scan status + StatusType to canonical OrderStatus (including full RTO states). */
    public static OrderStatus mapDelhiveryStatus(String rawStatus, String rawStatusType, OrderStatus currentStatus) {
        // Terminal states cannot regress from late or out-of-order carrier webhooks
        if (currentStatus == OrderStatus.DELIVERED
                || currentStatus == OrderStatus.RTO_DELIVERED
                || currentStatus == OrderStatus.CANCELLED) {
            return currentStatus;
        }

        String status = rawStatus == null ? "" : rawStatus.trim().toUpperCase(Locale.ROOT);
        String type = rawStatusType == null ? "" : rawStatusType.trim().toUpperCase(Locale.ROOT);

        // 1. RTO Lifecycle (StatusType == RT or status mentions RTO / Returned)
        if ("RT".equals(type) || status.contains("RTO") || status.contains("RETURN")) {
            if (status.contains("DELIVERED") || status.contains("RETURNED") || "DL".equals(type)) {
                return OrderStatus.RTO_DELIVERED;
            }
            if (status.contains("IN TRANSIT") || status.contains("TRANSIT") || status.contains("DISPATCHED")) {
                return OrderStatus.RTO_IN_TRANSIT;
            }
            return OrderStatus.RTO_INITIATED;
        }

        // 2. Forward Delivery Lifecycle
        if ("DL".equals(type) || status.equals("DELIVERED")) {
            return OrderStatus.DELIVERED;
        }
        if (status.contains("OUT FOR DELIVERY") || ("UD".equals(type) && status.equals("DISPATCHED"))) {
            return OrderStatus.OUT_FOR_DELIVERY;
        }
        if (status.contains("IN TRANSIT") || status.equals("PENDING")) {
            return OrderStatus.IN_TRANSIT;
        }
        if (status.contains("PICKED") || status.equals("DISPATCHED") || status.contains("SHIPPED")) {
            return OrderStatus.SHIPPED;
        }
        if (status.contains("MANIFEST")) {
            return currentStatus == OrderStatus.CONFIRMED ? OrderStatus.CONFIRMED : OrderStatus.PACKED;
        }
        return currentStatus;
    }

    private void applyDelhiveryScan(
            Order order,
            Order.SellerFulfillment fulfillment,
            Order.Shipment shipment,
            String statusName,
            String statusType,
            String location,
            String instructions,
            String actor) {
        LocalDateTime now = LocalDateTime.now();
        OrderStatus current = parseOrderStatus(order.getOverallStatus());
        OrderStatus mapped = mapDelhiveryStatus(statusName, statusType, current);

        Order.TrackingScan scan = new Order.TrackingScan();
        scan.setStatus(statusName);
        scan.setStatusType(statusType);
        scan.setLocation(location);
        scan.setInstructions(instructions);
        scan.setScannedAt(now);
        shipment.getTrackingEvents().add(scan);
        shipment.setLastTrackingUpdateAt(now);
        shipment.setLastTrackingDescription(firstNonBlank(instructions, statusName, mapped.name()));

        if (mapped == OrderStatus.SHIPPED && shipment.getPickupConfirmedAt() == null) {
            shipment.setPickupConfirmedAt(now);
            shipment.setStatus(ShipmentStatus.IN_TRANSIT.name());
        } else if (mapped == OrderStatus.IN_TRANSIT || mapped == OrderStatus.OUT_FOR_DELIVERY) {
            shipment.setStatus(ShipmentStatus.IN_TRANSIT.name());
        } else if (mapped == OrderStatus.DELIVERED) {
            shipment.setDeliveredAt(now);
            shipment.setStatus(ShipmentStatus.DELIVERED.name());
            if (order.getPayment() != null && PaymentMethod.COD.name().equalsIgnoreCase(order.getPayment().getPaymentType())) {
                order.getPayment().setStatus(PaymentStatus.CAPTURED.name());
                order.getPayment().setCodDue(BigDecimal.ZERO);
                order.getPayment().setRemainingAmount(BigDecimal.ZERO);
            }
        } else if (mapped == OrderStatus.RTO_INITIATED) {
            if (shipment.getRtoInitiatedAt() == null) shipment.setRtoInitiatedAt(now);
            if (hasText(instructions)) shipment.setRtoReason(instructions);
            shipment.setStatus(ShipmentStatus.RTO.name());
        } else if (mapped == OrderStatus.RTO_IN_TRANSIT) {
            if (shipment.getRtoInitiatedAt() == null) shipment.setRtoInitiatedAt(now);
            shipment.setStatus(ShipmentStatus.RTO.name());
        } else if (mapped == OrderStatus.RTO_DELIVERED) {
            if (shipment.getRtoInitiatedAt() == null) shipment.setRtoInitiatedAt(now);
            shipment.setRtoDeliveredAt(now);
            shipment.setStatus(ShipmentStatus.RTO.name());
        }

        if (mapped != current) {
            String note = "Delhivery update: " + firstNonBlank(statusName, mapped.name())
                    + (hasText(location) ? " (" + location + ")" : "")
                    + (hasText(instructions) ? " - " + instructions : "");
            applyStatusToOrder(order, mapped.name(), now, actor, note);
        } else {
            order.setUpdatedAt(now);
        }
    }

    private String fetchPackingSlipUrl(String waybill) {
        String endpoint = baseUrl + "/api/p/packing_slip?wbns=" + waybill + "&pdf=true";
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    endpoint, HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Map<String, Object> parsed = objectMapper.readValue(resp.getBody(), new TypeReference<>() {});
                Object pkgsObj = parsed.get("packages");
                if (pkgsObj instanceof List<?> pkgs && !pkgs.isEmpty() && pkgs.getFirst() instanceof Map<?, ?> first) {
                    String pdfUrl = firstNonBlank(asString(first.get("pdf_download_link")), asString(first.get("pdf_encoding")));
                    if (hasText(pdfUrl)) return pdfUrl;
                }
            }
        } catch (Exception ex) {
            log.warn("[DELHIVERY] Packing slip metadata fetch failed for {}: {}", waybill, ex.getMessage());
        }
        return endpoint;
    }

    private byte[] tryFetchLabelPdfBytes(String waybill, String labelRef) {
        try {
            if (labelRef.startsWith("http://") || labelRef.startsWith("https://")) {
                HttpEntity<Void> req = labelRef.startsWith(baseUrl) ? new HttpEntity<>(authHeaders()) : new HttpEntity<>(new HttpHeaders());
                ResponseEntity<byte[]> resp = restTemplate.exchange(labelRef, HttpMethod.GET, req, byte[].class);
                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null && resp.getBody().length > 0) {
                    return resp.getBody();
                }
            }
        } catch (Exception ex) {
            log.warn("[DELHIVERY] Unable to stream label PDF bytes for {}: {}", waybill, ex.getMessage());
        }
        return null;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Token " + apiToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private Order getOrder(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
    }

    private Order.SellerFulfillment ensurePrimaryFulfillment(Order order) {
        if (order.getSellerFulfillments() == null || order.getSellerFulfillments().isEmpty()) {
            Order.SellerFulfillment group = new Order.SellerFulfillment();
            group.setId(new ObjectId().toHexString());
            group.setStatus(normalizeStatus(order.getOverallStatus()));
            group.setShipments(new ArrayList<>());
            order.setSellerFulfillments(new ArrayList<>(List.of(group)));
        }
        return order.getSellerFulfillments().getFirst();
    }

    private Order.Shipment findPrimaryShipment(Order.SellerFulfillment fulfillment) {
        if (fulfillment.getShipments() == null || fulfillment.getShipments().isEmpty()) return null;
        return fulfillment.getShipments().getFirst();
    }

    private void applyStatusToOrder(Order order, String newStatus, LocalDateTime now, String actor, String note) {
        order.setOverallStatus(newStatus);
        order.setUpdatedAt(now);
        if (order.getSellerFulfillments() != null) {
            for (Order.SellerFulfillment f : order.getSellerFulfillments()) {
                f.setStatus(newStatus);
            }
        }
        if (order.getItems() != null) {
            for (OrderItem i : order.getItems()) {
                i.setItemStatus(newStatus);
            }
        }
        OrderStatusEvent event = new OrderStatusEvent();
        event.setStatus(newStatus);
        event.setOccurredAt(now);
        event.setActor(actor);
        event.setNote(note);
        if (order.getStatusHistory() == null) {
            order.setStatusHistory(new ArrayList<>());
        }
        order.getStatusHistory().add(event);
    }

    private void broadcastOrderUpdate(Order saved) {
        if (webSocketService == null || saved == null) {
            return;
        }
        Map<String, Object> payload = Map.of(
                "orderId", saved.getId(),
                "orderNumber", saved.getOrderNumber(),
                "status", saved.getOverallStatus());
        webSocketService.sendToRole("ADMIN", "orders:updated", payload);
        if (hasText(saved.getCustomerId())) {
            webSocketService.sendToUser(saved.getCustomerId(), "orders:updated", payload);
        }
        if (saved.getSellerFulfillments() != null) {
            saved.getSellerFulfillments().stream()
                    .map(Order.SellerFulfillment::getSellerId)
                    .filter(DelhiveryOneService::hasText)
                    .distinct()
                    .forEach(sellerId -> webSocketService.sendToUser(sellerId, "orders:updated", payload));
        }
    }

    private static OrderStatus parseOrderStatus(String raw) {
        if (raw == null || raw.isBlank()) return OrderStatus.PENDING;
        try {
            return OrderStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return OrderStatus.PENDING;
        }
    }

    private static String normalizeStatus(String raw) {
        return raw == null || raw.isBlank() ? OrderStatus.PENDING.name() : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (hasText(v)) return v.trim();
        }
        return null;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public record LabelDownloadResult(
            String orderId,
            String orderNumber,
            String waybill,
            String labelUrl,
            byte[] pdfBytes,
            String overallStatus,
            boolean transitionedToPacked) {}
}
