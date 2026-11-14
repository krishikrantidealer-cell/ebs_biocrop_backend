package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.response.ShiprocketOrderResult;
import com.ebs.biocrop.dto.response.ShiprocketServiceabilityResponse;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.OrderAddressSnapshot;
import com.ebs.biocrop.entity.OrderItem;

import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.service.ShiprocketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ShiprocketServiceImpl implements ShiprocketService {

    private static final Logger log = LoggerFactory.getLogger(ShiprocketServiceImpl.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String REDIS_TOKEN_KEY = "shiprocket:auth:token";

    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final HttpClient httpClient;

    @Value("${app.shiprocket.enabled:false}")
    private boolean enabled;

    @Value("${app.shiprocket.url:https://apiv2.shiprocket.in/v1/external}")
    private String baseUrl = "https://apiv2.shiprocket.in/v1/external";

    @Value("${app.shiprocket.email:}")
    private String email = "";

    @Value("${app.shiprocket.password:}")
    private String password = "";

    @Value("${app.shiprocket.pickup-location:}")
    private String pickupLocation = "";

    @Value("${app.shiprocket.pickup-pincode:}")
    private String pickupPincode = "";

    @Autowired
    public ShiprocketServiceImpl(Environment environment, ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this(environment, objectMapper, redisTemplate, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    public ShiprocketServiceImpl(Environment environment, ObjectMapper objectMapper, StringRedisTemplate redisTemplate, HttpClient httpClient) {
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public boolean isConfigured() {
        return enabled && email != null && !email.isBlank()
                && password != null && !password.isBlank()
                && !email.contains("placeholder");
    }

    private String getAuthToken() {
        if (!isConfigured()) {
            return null;
        }
        try {
            String cachedToken = redisTemplate.opsForValue().get(REDIS_TOKEN_KEY);
            if (cachedToken != null && !cachedToken.isBlank()) {
                return cachedToken;
            }
        } catch (Exception ignored) {
            // Redis cache fallback
        }

        try {
            Map<String, String> creds = Map.of("email", email, "password", password);
            String json = objectMapper.writeValueAsString(creds);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/auth/login"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(12))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(response.body());
                String token = root.path("token").asText();
                if (token != null && !token.isBlank()) {
                    try {
                        redisTemplate.opsForValue().set(REDIS_TOKEN_KEY, token, Duration.ofDays(8));
                    } catch (Exception ignored) {}
                    return token;
                }
            }
            log.warn("Shiprocket auth login failed with status code [{}]: {}", response.statusCode(), response.body());
        } catch (Exception e) {
            log.error("Exception connecting to Shiprocket auth login: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public ShiprocketServiceabilityResponse checkServiceability(String deliveryPincode, double weightKg, boolean isCod) {
        if (deliveryPincode == null || deliveryPincode.trim().length() != 6) {
            return new ShiprocketServiceabilityResponse(deliveryPincode, false, 0, "", 0.0, "Invalid pincode");
        }

        String token = getAuthToken();
        if (token != null) {
            try {
                String url = String.format("%s/courier/serviceability/?pickup_postcode=%s&delivery_postcode=%s&weight=%.2f&cod=%d",
                        baseUrl, pickupPincode, deliveryPincode.trim(), weightKg > 0 ? weightKg : 0.5, isCod ? 1 : 0);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode data = root.path("data");
                    JsonNode availableCouriers = data.path("available_courier_companies");
                    if (availableCouriers.isArray() && !availableCouriers.isEmpty()) {
                        JsonNode bestCourier = availableCouriers.get(0);
                        String courierName = bestCourier.path("courier_name").asText("Express Surface");
                        double rate = bestCourier.path("rate").asDouble(0.0);
                        int etd = bestCourier.path("estimated_delivery_days").asInt(4);
                        return new ShiprocketServiceabilityResponse(
                                deliveryPincode,
                                true,
                                etd > 0 ? etd : 3,
                                courierName,
                                rate,
                                "Delivery available via " + courierName
                        );
                    }
                }
            } catch (Exception e) {
                log.warn("Error calling Shiprocket serviceability API: {}", e.getMessage());
            }
        }

        if (!isDevelopmentProfile()) {
            return new ShiprocketServiceabilityResponse(
                    deliveryPincode, false, 0, "", 0.0,
                    "Shiprocket serviceability is not configured");
        }

        // Reliable default estimate for Indian postal codes
        int estimatedDays = deliveryPincode.startsWith("1") ? 2 : (deliveryPincode.startsWith("2") || deliveryPincode.startsWith("3")) ? 3 : 4;
        return new ShiprocketServiceabilityResponse(
                deliveryPincode,
                true,
                estimatedDays,
                "Delhivery Surface (Shiprocket)",
                0.0,
                "Delivery available in " + estimatedDays + " business days"
        );
    }

    @Override
    public ShiprocketOrderResult createOrderAndShipment(Order order) {
        return doCreateShiprocketShipment(order, null, order.getItems() != null ? order.getItems() : List.of(), null);
    }

    @Override
    public ShiprocketOrderResult createFulfillmentShipment(Order order, String fulfillmentId) {
        Order.SellerFulfillment fulfillment = (order.getSellerFulfillments() == null ? List.<Order.SellerFulfillment>of() : order.getSellerFulfillments()).stream()
                .filter(f -> Objects.equals(f.getId(), fulfillmentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Seller fulfillment [" + fulfillmentId + "] not found in order " + order.getId()));

        List<String> itemIds = fulfillment.getItemOrderIds() == null ? List.of() : fulfillment.getItemOrderIds();
        return createFulfillmentShipment(order, fulfillmentId, itemIds);
    }

    @Override
    public ShiprocketOrderResult createFulfillmentShipment(Order order, String fulfillmentId, List<String> itemIds) {
        return createFulfillmentShipment(order, fulfillmentId, itemIds, null);
    }

    @Override
    public ShiprocketOrderResult createFulfillmentShipment(Order order, String fulfillmentId, List<String> itemIds,
                                                            Order.Shipment packageDetails) {
        Order.SellerFulfillment fulfillment = (order.getSellerFulfillments() == null ? List.<Order.SellerFulfillment>of() : order.getSellerFulfillments()).stream()
                .filter(f -> Objects.equals(f.getId(), fulfillmentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Seller fulfillment [" + fulfillmentId + "] not found in order " + order.getId()));
        List<OrderItem> items = (order.getItems() == null ? List.<OrderItem>of() : order.getItems()).stream()
                .filter(item -> itemIds != null && itemIds.contains(item.getItemOrderId()))
                .toList();

        return doCreateShiprocketShipment(order, fulfillment, items, packageDetails);
    }

    private ShiprocketOrderResult doCreateShiprocketShipment(Order order, Order.SellerFulfillment fulfillment,
                                                             List<OrderItem> items, Order.Shipment packageDetails) {
        String token = getAuthToken();
        OrderAddressSnapshot address = order.getShippingAddress();
        if (address == null) {
            address = order.getBillingAddress();
        }

        String recipientName = address == null ? null : address.getName();
        String recipientPhone = address == null ? null : address.getPhoneNumber();
        String pincode = address == null ? null : address.getPincode();
        String city = address == null ? null : address.getCityTehsil();
        String state = address == null ? null : address.getState();
        String village = address == null ? null : address.getVillageArea();

        String externalOrderId = packageDetails != null && packageDetails.getBookingReference() != null
                ? packageDetails.getBookingReference()
                : fulfillment != null && fulfillment.getId() != null
                ? order.getOrderNumber() + "-F" + fulfillment.getId().substring(Math.max(0, fulfillment.getId().length() - 6))
                : order.getOrderNumber();

        if (token != null) {
            try {
                requireNonBlank(order.getOrderNumber(), "Order number");
                requireNonBlank(pickupLocation, "Shiprocket pickup location");
                requireNonBlank(recipientName, "Shipping recipient name");
                requireNonBlank(recipientPhone, "Shipping recipient phone");
                requireNonBlank(pincode, "Shipping pincode");
                requireNonBlank(city, "Shipping city");
                requireNonBlank(state, "Shipping state");
                requireNonBlank(village, "Shipping address line");
                if (order.getPlacedAt() == null) {
                    throw new AppException("Order placement timestamp is required for Shiprocket booking", HttpStatus.BAD_REQUEST);
                }
                if (items == null || items.isEmpty()) {
                    throw new AppException("A shipment must contain at least one order item", HttpStatus.BAD_REQUEST);
                }
                if (packageDetails == null || !isPositive(packageDetails.getPackageWeightKg())
                        || !isPositive(packageDetails.getLengthCm()) || !isPositive(packageDetails.getBreadthCm())
                        || !isPositive(packageDetails.getHeightCm())) {
                    throw new AppException("Package weight and dimensions are required for Shiprocket booking", HttpStatus.BAD_REQUEST);
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("order_id", externalOrderId);
                payload.put("order_date", order.getPlacedAt().format(DATE_FORMATTER));
                payload.put("pickup_location", pickupLocation);

                payload.put("billing_customer_name", recipientName);
                payload.put("billing_last_name", "");
                payload.put("billing_address", village);
                payload.put("billing_address_2", (address != null && address.getAddress2() != null) ? address.getAddress2() : city);
                payload.put("billing_city", city);
                payload.put("billing_pincode", pincode);
                payload.put("billing_state", state);
                payload.put("billing_country", "India");
                payload.put("billing_email", "support@ebsbiocrop.com");
                payload.put("billing_phone", recipientPhone);
                payload.put("shipping_is_billing", true);

                List<Map<String, Object>> itemMaps = new ArrayList<>();
                BigDecimal amountAlreadyCollected = BigDecimal.ZERO;
                BigDecimal amountDueAtDelivery = BigDecimal.ZERO;
                for (OrderItem item : items) {
                    Map<String, Object> itemMap = new LinkedHashMap<>();
                    String itemTitle = item.getProductSnapshot() == null ? null : item.getProductSnapshot().getTitle();
                    String itemSku = item.getVariantSku() != null ? item.getVariantSku() : item.getProductSku();
                    requireNonBlank(itemTitle, "Order item product title");
                    requireNonBlank(itemSku, "Order item SKU");
                    itemMap.put("name", itemTitle);
                    itemMap.put("sku", itemSku);
                    int qty = item.getQuantity() == null ? 0 : item.getQuantity();
                    if (qty <= 0 || item.getUnitPrice() == null || item.getUnitPrice().signum() < 0) {
                        throw new AppException("Order item quantity and price must be valid for Shiprocket booking", HttpStatus.BAD_REQUEST);
                    }
                    double unitPrice = item.getUnitPrice().doubleValue();
                    itemMap.put("units", qty);
                    itemMap.put("selling_price", unitPrice);
                    itemMap.put("discount", 0);
                    itemMaps.add(itemMap);
                    amountAlreadyCollected = amountAlreadyCollected.add(item.getAdvanceAllocatedAtCheckout() == null
                            ? BigDecimal.ZERO : item.getAdvanceAllocatedAtCheckout());
                    amountDueAtDelivery = amountDueAtDelivery.add(item.getCodDueAtCheckout() == null
                            ? BigDecimal.ZERO : item.getCodDueAtCheckout());
                }
                payload.put("order_items", itemMaps);

                boolean hasCodDue = items.stream().anyMatch(i -> i.getCodDueAtCheckout() != null && i.getCodDueAtCheckout().signum() > 0);
                payload.put("payment_method", hasCodDue ? "COD" : "Prepaid");
                // Use the exact amount due for this package: already-collected advance plus the
                // package's COD balance (which includes any per-item COD charge allocated at checkout).
                BigDecimal shipmentTotal = amountAlreadyCollected.add(amountDueAtDelivery).setScale(2, java.math.RoundingMode.HALF_UP);
                payload.put("sub_total", shipmentTotal);
                if (hasCodDue && amountAlreadyCollected.signum() > 0) {
                    if (amountAlreadyCollected.compareTo(shipmentTotal) >= 0) {
                        throw new AppException("The partial COD advance must be less than the package total", HttpStatus.BAD_REQUEST);
                    }
                    // Supported by Shiprocket's adhoc order API: the courier collects
                    // sub_total minus partial_cod_collected at delivery.
                    payload.put("partial_cod_collected", amountAlreadyCollected.setScale(2, java.math.RoundingMode.HALF_UP));
                }
                payload.put("length", packageDetails.getLengthCm());
                payload.put("breadth", packageDetails.getBreadthCm());
                payload.put("height", packageDetails.getHeightCm());
                payload.put("weight", packageDetails.getPackageWeightKg());

                String json = objectMapper.writeValueAsString(payload);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/orders/create/adhoc"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    JsonNode root = objectMapper.readTree(response.body());
                    long srOrderId = root.path("order_id").asLong();
                    long shipmentId = root.path("shipment_id").asLong();
                    String status = root.path("status").asText("NEW");

                    // Request AWB assignment
                    String awbCode = assignAwb(shipmentId, token);
                    String courier = "Delhivery Surface (Shiprocket)";
                    String trackingUrl = "https://shiprocket.co/tracking/" + (awbCode != null ? awbCode : externalOrderId);

                    log.info("Shiprocket shipment successfully created for order [{}] (fulfillment [{}]). Order ID: {}, Shipment ID: {}, AWB: {}",
                            order.getOrderNumber(), fulfillment != null ? fulfillment.getId() : "ALL", srOrderId, shipmentId, awbCode);
                    return new ShiprocketOrderResult(srOrderId, shipmentId, awbCode, courier, "1", trackingUrl, status);
                } else {
                    log.warn("Shiprocket adhoc order creation returned status [{}]: {}", response.statusCode(), response.body());
                }
            } catch (Exception e) {
                log.error("Exception creating Shiprocket order: {}", e.getMessage(), e);
            }
        }

        if (!isDevelopmentProfile()) {
            throw new AppException("Shiprocket shipment booking is unavailable; integration failed or is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        // Generated mock Shiprocket logistics result only in the dev profile.
        long mockSrOrderId = 10000000L + Math.abs((externalOrderId).hashCode() % 8999999L);
        long mockShipmentId = mockSrOrderId + 1;
        String mockAwb = "SRK" + (mockSrOrderId % 100000000L) + "IN";
        String courier = "Delhivery Surface (Shiprocket)";
        String trackingUrl = "https://shiprocket.co/tracking/" + mockAwb;

        log.info("[SHIPROCKET-MOCK] Generated logistics assignment: AWB [{}] via [{}] for order [{}] fulfillment [{}]",
                mockAwb, courier, order.getOrderNumber(), fulfillment != null ? fulfillment.getId() : "ALL");

        return new ShiprocketOrderResult(
                mockSrOrderId,
                mockShipmentId,
                mockAwb,
                courier,
                "10",
                trackingUrl,
                "READY_FOR_PICKUP"
        );
    }

    private void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AppException(field + " is required for Shiprocket booking", HttpStatus.BAD_REQUEST);
        }
    }

    private boolean isPositive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }

    private String assignAwb(long shipmentId, String token) {
        try {
            Map<String, Object> payload = Map.of("shipment_id", shipmentId);
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/courier/assign/awb"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(12))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode awb = root.path("response").path("data").path("awb_code");
                if (!awb.isMissingNode() && !awb.asText().isBlank()) {
                    return awb.asText();
                }
            }
        } catch (Exception e) {
            log.warn("Shiprocket AWB assignment error: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public String trackShipment(String awbCode) {
        if (awbCode == null || awbCode.isBlank()) return "{}";
        String token = getAuthToken();
        if (token != null) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/courier/track/awb/" + awbCode.trim()))
                        .header("Authorization", "Bearer " + token)
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response.body();
                }
            } catch (Exception ignored) {}
        }
        if (!isDevelopmentProfile()) {
            return "{\"tracking\": {\"awb\": \"" + awbCode + "\", \"status\": \"UNAVAILABLE\"}}";
        }
        return "{\"tracking\": {\"awb\": \"" + awbCode + "\", \"status\": \"In Transit\", \"courier\": \"Delhivery Surface\"}}";
    }

    private boolean isDevelopmentProfile() {
        return environment != null && environment.acceptsProfiles(Profiles.of("dev"));
    }
}
