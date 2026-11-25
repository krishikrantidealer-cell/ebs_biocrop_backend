package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.OrderAddressSnapshot;
import com.ebs.biocrop.entity.OrderItem;
import com.ebs.biocrop.entity.OrderStatusEvent;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.enums.OrderStatus;
import com.ebs.biocrop.entity.enums.ShipmentStatus;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.websocket.WebSocketService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Integrates with Velocity Shipping (shazam.velocity.in) to push new confirmed orders
 * into Velocity's Pending AWB queue and receive real-time AWB/tracking webhooks.
 */
@Service
public class VelocityService {

    private static final Logger log = LoggerFactory.getLogger(VelocityService.class);
    private static final DateTimeFormatter VELOCITY_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OrderRepository orderRepository;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;
    private final WebSocketService webSocketService;
    private final HttpClient httpClient;

    @Value("${velocity.enabled:false}")
    private boolean enabled;

    @Value("${velocity.base-url:https://shazam.velocity.in}")
    private String baseUrl;

    @Value("${velocity.api-token:}")
    private String apiToken;

    @Value("${velocity.warehouse-id:WHBAER}")
    private String warehouseId;

    @Value("${velocity.pickup-location:Website Dispatch}")
    private String pickupLocation;

    @Value("${velocity.webhook-token:}")
    private String webhookToken;

    public VelocityService(
            OrderRepository orderRepository,
            MongoTemplate mongoTemplate,
            ObjectMapper objectMapper,
            WebSocketService webSocketService) {
        this.orderRepository = orderRepository;
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
        this.webSocketService = webSocketService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record LabelDownloadResult(
            String orderId,
            String orderNumber,
            String waybill,
            String labelUrl,
            String overallStatus,
            boolean transitionedToPacked,
            byte[] pdfBytes
    ) {}

    public boolean isConfigured() {
        return enabled && hasText(apiToken);
    }

    /** Confirms a pending order and pushes it into Velocity's Pending AWB queue. */
    public Order confirmOrder(String actorId, String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        if (OrderStatus.CANCELLED.name().equalsIgnoreCase(order.getOverallStatus())) {
            throw new AppException("Cannot confirm a cancelled order", HttpStatus.CONFLICT);
        }
        order.setOverallStatus(OrderStatus.CONFIRMED.name());
        for (Order.SellerFulfillment sf : order.getSellerFulfillments()) {
            sf.setStatus(OrderStatus.CONFIRMED.name());
            if (sf.getAcceptedAt() == null) sf.setAcceptedAt(LocalDateTime.now());
        }
        OrderStatusEvent evt = new OrderStatusEvent();
        evt.setStatus(OrderStatus.CONFIRMED.name());
        evt.setOccurredAt(LocalDateTime.now());
        evt.setActor(hasText(actorId) ? actorId : "ADMIN");
        evt.setNote("Order confirmed for Velocity Shipping");
        order.getStatusHistory().add(evt);
        order.setUpdatedAt(LocalDateTime.now());
        Order saved = orderRepository.save(order);
        broadcastOrderUpdate(saved, "ORDER_CONFIRMED");
        return isConfigured() ? pushPendingOrder(saved) : saved;
    }

    /** Explicitly pushes an order to Velocity's Pending AWB queue from Admin/Seller panel. */
    public Order pushOrderToVelocity(String actorId, String orderId) {
        if (!isConfigured()) {
            throw new AppException("Velocity Shipping is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        if (OrderStatus.CANCELLED.name().equalsIgnoreCase(order.getOverallStatus())) {
            throw new AppException("Cannot push a cancelled order to Velocity", HttpStatus.CONFLICT);
        }
        return pushPendingOrder(order);
    }

    /** Marks order PACKED and returns stored Velocity label URL / AWB details if available. */
    public LabelDownloadResult getLabelInfoAndMarkPacked(String actorId, String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        Order.SellerFulfillment sf = ensurePrimaryFulfillment(order);
        Order.Shipment shipment = ensurePrimaryShipment(sf);
        boolean transitioned = false;
        if (!OrderStatus.PACKED.name().equalsIgnoreCase(order.getOverallStatus())
                && !OrderStatus.DISPATCHED.name().equalsIgnoreCase(order.getOverallStatus())
                && !OrderStatus.DELIVERED.name().equalsIgnoreCase(order.getOverallStatus())) {
            order.setOverallStatus(OrderStatus.PACKED.name());
            sf.setStatus(OrderStatus.PACKED.name());
            transitioned = true;
        }
        shipment.setLabelDownloadedAt(LocalDateTime.now());
        shipment.setLabelDownloadedBy(hasText(actorId) ? actorId : "ADMIN");
        order.setUpdatedAt(LocalDateTime.now());
        Order saved = orderRepository.save(order);
        if (transitioned) {
            broadcastOrderUpdate(saved, "ORDER_PACKED");
        }
        return new LabelDownloadResult(
                saved.getId(),
                saved.getOrderNumber(),
                shipment.getTrackingNumber(),
                shipment.getLabelReference(),
                saved.getOverallStatus(),
                transitioned,
                null
        );
    }

    /** Returns latest persisted order tracking state (synced live via Velocity webhooks). */
    public Order syncTrackingForOrder(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
    }

    /** Verifies RTO return receipt and restocks returned quantities into product variant inventory. */
    public Order restockRtoOrder(String actorId, String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        Order.SellerFulfillment sf = ensurePrimaryFulfillment(order);
        Order.Shipment shipment = ensurePrimaryShipment(sf);
        if (Boolean.TRUE.equals(shipment.getRtoRestocked())) {
            return order;
        }
        for (OrderItem item : order.getItems()) {
            if (hasText(item.getProductId()) && hasText(item.getVariantId()) && item.getQuantity() != null && item.getQuantity() > 0) {
                Query q = Query.query(Criteria.where("_id").is(item.getProductId())
                        .and("variants.variantId").is(item.getVariantId()));
                mongoTemplate.updateFirst(q, new Update().inc("variants.$.stock", item.getQuantity()), Product.class);
            }
        }
        shipment.setRtoRestocked(true);
        shipment.setRtoRestockedAt(LocalDateTime.now());
        order.setOverallStatus(OrderStatus.RTO_DELIVERED.name());
        sf.setStatus(OrderStatus.RTO_DELIVERED.name());
        OrderStatusEvent evt = new OrderStatusEvent();
        evt.setStatus(OrderStatus.RTO_DELIVERED.name());
        evt.setOccurredAt(LocalDateTime.now());
        evt.setActor(hasText(actorId) ? actorId : "ADMIN");
        evt.setNote("RTO return received and inventory restocked");
        order.getStatusHistory().add(evt);
        order.setUpdatedAt(LocalDateTime.now());
        Order saved = orderRepository.save(order);
        broadcastOrderUpdate(saved, "RTO_RESTOCKED");
        return saved;
    }

    /** Schedules asynchronous push of confirmed orders after any active transaction commits. */
    public void pushPendingOrdersAsync(List<Order> orders) {
        if (!isConfigured() || orders == null || orders.isEmpty()) {
            return;
        }
        List<String> orderIds = orders.stream()
                .filter(o -> o != null && hasText(o.getId()) && OrderStatus.CONFIRMED.name().equalsIgnoreCase(o.getOverallStatus()))
                .map(Order::getId)
                .toList();
        if (orderIds.isEmpty()) {
            return;
        }
        Runnable task = () -> CompletableFuture.runAsync(() -> {
            for (String orderId : orderIds) {
                try {
                    orderRepository.findById(orderId).ifPresent(this::pushPendingOrder);
                } catch (Exception ex) {
                    log.warn("[VELOCITY] Async push failed for order {}: {}", orderId, ex.getMessage());
                }
            }
        });
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /** Pushes a single confirmed order to Velocity's Pending AWB queue (no courier assigned yet). */
    public Order pushPendingOrder(Order order) {
        if (!isConfigured() || order == null) {
            return order;
        }
        Order.SellerFulfillment fulfillment = ensurePrimaryFulfillment(order);
        Order.Shipment shipment = ensurePrimaryShipment(fulfillment);
        if (hasText(shipment.getBookingReference()) && shipment.getBookingReference().startsWith("VEL:")) {
            return order;
        }
        if (hasText(shipment.getTrackingNumber())) {
            return order;
        }

        OrderAddressSnapshot addr = order.getShippingAddress() != null ? order.getShippingAddress() : order.getBillingAddress();
        if (addr == null) {
            log.warn("[VELOCITY] Skipping order {} — missing shipping/billing address", order.getOrderNumber());
            return order;
        }

        String phone = sanitizeIndianPhone(addr.getPhoneNumber());
        String fullAddress = joinNonBlank(", ", addr.getVillageArea(), addr.getAddress2(), addr.getDistrict());
        String city = firstNonBlank(addr.getCityTehsil(), addr.getDistrict(), "Bhopal");
        String state = firstNonBlank(addr.getState(), "Madhya Pradesh");
        String pincode = firstNonBlank(addr.getPincode(), "462039");
        String customerName = firstNonBlank(addr.getName(), order.getCustomerSnapshot() != null ? order.getCustomerSnapshot().getName() : null, "Customer");

        List<Map<String, Object>> itemsPayload = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            String title = item.getProductSnapshot() != null ? item.getProductSnapshot().getTitle() : "Product";
            String variant = item.getProductSnapshot() != null ? item.getProductSnapshot().getVariantLabel() : null;
            String itemName = hasText(variant) ? title + " (" + variant + ")" : title;
            String sku = firstNonBlank(item.getVariantSku(), item.getProductSku(), item.getProductCode(), item.getVariantId(), "EBS-ITEM");
            int qty = item.getQuantity() == null || item.getQuantity() <= 0 ? 1 : item.getQuantity();
            double price = item.getUnitPrice() != null ? item.getUnitPrice().doubleValue() : 0.0;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", itemName);
            row.put("sku", sku);
            row.put("units", qty);
            row.put("selling_price", price);
            itemsPayload.add(row);
        }

        BigDecimal codDue = order.getFinancialSummary() != null && order.getFinancialSummary().getCodDue() != null
                ? order.getFinancialSummary().getCodDue()
                : BigDecimal.ZERO;
        boolean isCod = codDue.compareTo(BigDecimal.ZERO) > 0
                || (order.getPayment() != null && "COD".equalsIgnoreCase(order.getPayment().getPaymentType()));

        double grandTotal = order.getTotalAmount() != null && order.getTotalAmount() > 0
                ? order.getTotalAmount()
                : (order.getSubtotalAmount() != null ? order.getSubtotalAmount() : 0.0);
        double shippingCharges = order.getShippingAmount() != null ? order.getShippingAmount() : 0.0;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_id", order.getOrderNumber());
        body.put("order_date", (order.getPlacedAt() != null ? order.getPlacedAt() : LocalDateTime.now()).format(VELOCITY_DATE_FMT));
        body.put("billing_customer_name", customerName);
        body.put("billing_address", fullAddress.isEmpty() ? city : fullAddress);
        body.put("billing_city", city);
        body.put("billing_pincode", pincode);
        body.put("billing_state", state);
        body.put("billing_country", firstNonBlank(addr.getCountry(), "India"));
        body.put("billing_phone", phone);
        body.put("shipping_is_billing", true);
        body.put("order_items", itemsPayload);
        body.put("payment_method", isCod ? "COD" : "Prepaid");
        body.put("sub_total", grandTotal);
        if (isCod) {
            body.put("cod_collectible", codDue.compareTo(BigDecimal.ZERO) > 0 ? codDue.doubleValue() : grandTotal);
        }
        body.put("shipping_charges", shippingCharges);
        body.put("length", shipment.getLengthCm() != null && shipment.getLengthCm() > 0 ? shipment.getLengthCm() : 10.0);
        body.put("breadth", shipment.getBreadthCm() != null && shipment.getBreadthCm() > 0 ? shipment.getBreadthCm() : 10.0);
        body.put("height", shipment.getHeightCm() != null && shipment.getHeightCm() > 0 ? shipment.getHeightCm() : 10.0);
        body.put("weight", shipment.getPackageWeightKg() != null && shipment.getPackageWeightKg() > 0 ? shipment.getPackageWeightKg() : 0.5);
        body.put("pickup_location", pickupLocation);
        body.put("warehouse_id", warehouseId);

        try {
            String json = objectMapper.writeValueAsString(body);
            String endpoint = baseUrl.replaceAll("/+$", "") + "/custom/api/v1/forward-order";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiToken.trim())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("[VELOCITY] Failed to push order {} (HTTP {}): {}", order.getOrderNumber(), resp.statusCode(), resp.body());
                return order;
            }

            Map<String, Object> parsed = objectMapper.readValue(resp.body(), new TypeReference<>() {});
            Map<String, Object> payloadNode = extractMap(parsed, "payload");
            if (payloadNode.isEmpty()) payloadNode = extractMap(parsed, "data");

            String velShipmentId = firstNonBlank(
                    asString(payloadNode.get("shipment_id")),
                    asString(payloadNode.get("order_id")),
                    order.getOrderNumber());

            shipment.setBookingReference("VEL:" + velShipmentId);
            shipment.setCarrier("Velocity Shipping (Pending AWB)");
            shipment.setStatus(ShipmentStatus.PENDING.name());
            shipment.setLastTrackingUpdateAt(LocalDateTime.now());
            shipment.setLastTrackingDescription("Pushed to Velocity Pending AWB queue (ID: " + velShipmentId + ")");

            OrderStatusEvent evt = new OrderStatusEvent();
            evt.setStatus(order.getOverallStatus());
            evt.setOccurredAt(LocalDateTime.now());
            evt.setActor("VELOCITY_SYNC");
            evt.setNote("Synced to Velocity Pending AWB queue (" + velShipmentId + ")");
            order.getStatusHistory().add(evt);
            order.setUpdatedAt(LocalDateTime.now());

            Order saved = orderRepository.save(order);
            broadcastOrderUpdate(saved, "VELOCITY_SYNCED");
            log.info("[VELOCITY] Pushed order {} to Pending AWB queue (velId={})", saved.getOrderNumber(), velShipmentId);
            return saved;
        } catch (Exception ex) {
            log.warn("[VELOCITY] Exception pushing order {}: {}", order.getOrderNumber(), ex.getMessage());
            return order;
        }
    }

    /** Processes incoming Velocity webhook updates (AWB assignment & tracking scans). */
    public Map<String, Object> processTrackingWebhook(Map<String, Object> payload, String providedToken) {
        if (hasText(webhookToken)) {
            String candidate = providedToken == null ? "" : providedToken.trim();
            if (candidate.startsWith("Bearer ")) {
                candidate = candidate.substring(7).trim();
            }
            if (!MessageDigest.isEqual(webhookToken.trim().getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8))) {
                throw new AppException("Invalid Velocity webhook token", HttpStatus.UNAUTHORIZED);
            }
        }
        if (payload == null || payload.isEmpty()) {
            return Map.of("status", "IGNORED_EMPTY");
        }

        Map<String, Object> data = extractMap(payload, "data");
        if (data.isEmpty()) data = payload;

        String orderNumber = firstNonBlank(
                asString(data.get("order_external_id")),
                asString(data.get("order_display_id")),
                asString(data.get("external_id")));
        String trackingNumber = firstNonBlank(
                asString(data.get("tracking_number")),
                asString(data.get("awb")),
                asString(data.get("awb_number")));
        String velShipmentId = asString(data.get("shipment_id"));
        String carrierName = firstNonBlank(asString(data.get("carrier_name")), asString(data.get("courier_name")));
        String rawStatus = firstNonBlank(asString(data.get("status")), asString(data.get("sub_status")), asString(payload.get("event")));

        Order order = findOrder(orderNumber, trackingNumber, velShipmentId);
        if (order == null) {
            return Map.of("status", "IGNORED_UNKNOWN_ORDER", "orderExternalId", String.valueOf(orderNumber));
        }

        Order.SellerFulfillment fulfillment = ensurePrimaryFulfillment(order);
        Order.Shipment shipment = ensurePrimaryShipment(fulfillment);

        if (hasText(trackingNumber)) {
            shipment.setTrackingNumber(trackingNumber);
        }
        if (hasText(carrierName)) {
            shipment.setCarrier(carrierName);
        }

        ShipmentStatus mapped = mapVelocityStatus(rawStatus);
        shipment.setStatus(mapped.name());
        shipment.setLastTrackingUpdateAt(LocalDateTime.now());
        shipment.setLastTrackingDescription(hasText(rawStatus) ? "Velocity: " + rawStatus : "Velocity update");

        Order.TrackingScan scan = new Order.TrackingScan();
        scan.setStatus(mapped.name());
        scan.setStatusType(asString(payload.get("event")));
        scan.setInstructions(rawStatus);
        scan.setScannedAt(LocalDateTime.now());
        shipment.getTrackingEvents().add(scan);

        if (mapped == ShipmentStatus.IN_TRANSIT || mapped == ShipmentStatus.OUT_FOR_DELIVERY) {
            order.setOverallStatus(OrderStatus.DISPATCHED.name());
            fulfillment.setStatus(OrderStatus.DISPATCHED.name());
            if (fulfillment.getDispatchedAt() == null) fulfillment.setDispatchedAt(LocalDateTime.now());
        } else if (mapped == ShipmentStatus.DELIVERED) {
            order.setOverallStatus(OrderStatus.DELIVERED.name());
            fulfillment.setStatus(OrderStatus.DELIVERED.name());
            if (shipment.getDeliveredAt() == null) shipment.setDeliveredAt(LocalDateTime.now());
        } else if (mapped == ShipmentStatus.RTO) {
            order.setOverallStatus(OrderStatus.RTO_INITIATED.name());
            fulfillment.setStatus(OrderStatus.RTO_INITIATED.name());
            if (shipment.getRtoInitiatedAt() == null) shipment.setRtoInitiatedAt(LocalDateTime.now());
        } else if (mapped == ShipmentStatus.CANCELLED) {
            order.setOverallStatus(OrderStatus.CANCELLED.name());
            fulfillment.setStatus(OrderStatus.CANCELLED.name());
        }

        order.setUpdatedAt(LocalDateTime.now());
        Order saved = orderRepository.save(order);
        broadcastOrderUpdate(saved, "VELOCITY_WEBHOOK");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "PROCESSED");
        out.put("orderNumber", saved.getOrderNumber());
        out.put("shipmentStatus", mapped.name());
        if (hasText(trackingNumber)) out.put("trackingNumber", trackingNumber);
        return out;
    }

    private Order findOrder(String orderNumber, String trackingNumber, String velShipmentId) {
        if (hasText(orderNumber)) {
            Order found = mongoTemplate.findOne(Query.query(Criteria.where("orderNumber").is(orderNumber)), Order.class);
            if (found != null) return found;
        }
        if (hasText(trackingNumber)) {
            Order found = mongoTemplate.findOne(
                    Query.query(Criteria.where("sellerFulfillments.shipments.trackingNumber").is(trackingNumber)), Order.class);
            if (found != null) return found;
        }
        if (hasText(velShipmentId)) {
            return mongoTemplate.findOne(
                    Query.query(Criteria.where("sellerFulfillments.shipments.bookingReference").is("VEL:" + velShipmentId)), Order.class);
        }
        return null;
    }

    private ShipmentStatus mapVelocityStatus(String raw) {
        if (!hasText(raw)) return ShipmentStatus.PENDING;
        String norm = raw.trim().toLowerCase(Locale.ROOT);
        if (norm.contains("deliver") && !norm.contains("undeliver") && !norm.contains("out_for") && !norm.contains("out for")) {
            return ShipmentStatus.DELIVERED;
        }
        if (norm.contains("out_for_delivery") || norm.contains("out for delivery")) {
            return ShipmentStatus.OUT_FOR_DELIVERY;
        }
        if (norm.contains("rto")) {
            return ShipmentStatus.RTO;
        }
        if (norm.contains("cancel")) {
            return ShipmentStatus.CANCELLED;
        }
        if (norm.contains("ndr") || norm.contains("undeliver") || norm.contains("fail")) {
            return ShipmentStatus.UNDELIVERED;
        }
        if (norm.contains("transit") || norm.contains("shipped") || norm.contains("picked")) {
            return ShipmentStatus.IN_TRANSIT;
        }
        if (norm.contains("manifest") || norm.contains("ready_for_pickup") || norm.contains("pickup") || norm.contains("awb")) {
            return ShipmentStatus.MANIFESTED;
        }
        return ShipmentStatus.PENDING;
    }

    private Order.SellerFulfillment ensurePrimaryFulfillment(Order order) {
        if (order.getSellerFulfillments().isEmpty()) {
            Order.SellerFulfillment sf = new Order.SellerFulfillment();
            sf.setFulfillmentId(UUID.randomUUID().toString());
            sf.setStatus(order.getOverallStatus());
            order.getSellerFulfillments().add(sf);
        }
        return order.getSellerFulfillments().getFirst();
    }

    private Order.Shipment ensurePrimaryShipment(Order.SellerFulfillment fulfillment) {
        if (fulfillment.getShipments().isEmpty()) {
            Order.Shipment s = new Order.Shipment();
            s.setShipmentId(UUID.randomUUID().toString());
            s.setStatus(ShipmentStatus.PENDING.name());
            fulfillment.getShipments().add(s);
        }
        return fulfillment.getShipments().getFirst();
    }

    private void broadcastOrderUpdate(Order order, String source) {
        if (webSocketService == null || order == null) return;
        Map<String, Object> payload = Map.of(
                "orderId", order.getId(),
                "orderNumber", order.getOrderNumber(),
                "status", order.getOverallStatus(),
                "source", source);
        webSocketService.sendToRole("ADMIN", "orders:updated", payload);
        webSocketService.sendToRole("SALES_AGENT", "orders:updated", payload);
        if (hasText(order.getCustomerId())) {
            webSocketService.sendToUser(order.getCustomerId(), "orders:updated", payload);
        }
    }

    private static String sanitizeIndianPhone(String raw) {
        if (!hasText(raw)) return "9999999999";
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() > 10) digits = digits.substring(digits.length() - 10);
        return digits.length() == 10 ? digits : "9999999999";
    }

    private static String joinNonBlank(String sep, String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (hasText(p)) out.add(p.trim());
        }
        return String.join(sep, out);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (hasText(v)) return v.trim();
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractMap(Map<String, Object> parent, String key) {
        if (parent == null) return Collections.emptyMap();
        Object val = parent.get(key);
        if (val instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Collections.emptyMap();
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
