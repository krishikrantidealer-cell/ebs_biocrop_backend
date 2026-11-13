package com.ebs.biocrop;

import com.ebs.biocrop.dto.response.ShiprocketOrderResult;
import com.ebs.biocrop.dto.response.ShiprocketServiceabilityResponse;
import com.ebs.biocrop.entity.*;
import com.ebs.biocrop.entity.enums.PaymentMethod;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.service.impl.ShiprocketServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ShiprocketServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    private ShiprocketServiceImpl shiprocketService;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        shiprocketService = new ShiprocketServiceImpl(environment, new ObjectMapper(), redisTemplate);
    }

    @Test
    @DisplayName("checkServiceability returns positive delivery estimate for 6-digit Indian pincode")
    void testCheckServiceability() {
        ShiprocketServiceabilityResponse response = shiprocketService.checkServiceability("132001", 1.5, false);
        assertNotNull(response);
        assertTrue(response.serviceable());
        assertTrue(response.estimatedDeliveryDays() > 0);
        assertNotNull(response.courierName());
    }

    @Test
    @DisplayName("createOrderAndShipment generates AWB, courier allocation and tracking URL")
    void testCreateOrderAndShipment() {
        Order order = buildSampleOrder();
        ShiprocketOrderResult result = shiprocketService.createOrderAndShipment(order);
        assertNotNull(result);
        assertNotNull(result.shiprocketOrderId());
        assertNotNull(result.shipmentId());
        assertNotNull(result.awbCode());
        assertNotNull(result.courierName());
        assertTrue(result.trackingUrl().contains("shiprocket.co/tracking/"));
    }

    @Test
    @DisplayName("createFulfillmentShipment books shipment embedded for target seller fulfillment without creating separate orders")
    void testCreateFulfillmentShipment() {
        Order order = buildSampleOrder();

        // Add 2 seller fulfillments
        Order.SellerFulfillment f1 = new Order.SellerFulfillment();
        f1.setId("ful-1");
        f1.setSellerId("seller-1");
        f1.setItemOrderIds(List.of("item-1"));
        f1.setStatus("PACKED");

        Order.SellerFulfillment f2 = new Order.SellerFulfillment();
        f2.setId("ful-2");
        f2.setSellerId("seller-2");
        f2.setItemOrderIds(List.of("item-2"));
        f2.setStatus("AWAITING_SELLER_RESPONSE");

        order.setSellerFulfillments(new ArrayList<>(List.of(f1, f2)));

        // Book shipment specifically for fulfillment 1
        ShiprocketOrderResult result = shiprocketService.createFulfillmentShipment(order, "ful-1");
        assertNotNull(result);
        assertNotNull(result.shipmentId());
        assertNotNull(result.awbCode());
        assertEquals("Delhivery Surface (Shiprocket)", result.courierName());
        assertEquals("READY_FOR_PICKUP", result.status());

        // Verify invalid fulfillment throws exception
        assertThrows(IllegalArgumentException.class, () ->
                shiprocketService.createFulfillmentShipment(order, "non-existent-ful"));
    }

    @Test
    @DisplayName("partial COD shipment sends the paid advance as partial_cod_collected and only leaves the remainder collectible")
    void partialCodAmountIsPassedToShiprocket() throws Exception {
        AtomicReference<String> adhocRequestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/login", exchange -> respond(exchange, "{\"token\":\"test-token\"}"));
        server.createContext("/orders/create/adhoc", exchange -> {
            adhocRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"order_id\":123,\"shipment_id\":456,\"status\":\"NEW\"}");
        });
        server.createContext("/courier/assign/awb", exchange -> respond(exchange,
                "{\"response\":{\"data\":{\"awb_code\":\"AWB-PARTIAL-123\"}}}"));
        server.start();
        try {
            ReflectionTestUtils.setField(shiprocketService, "enabled", true);
            ReflectionTestUtils.setField(shiprocketService, "email", "test@example.com");
            ReflectionTestUtils.setField(shiprocketService, "password", "test-password");
            ReflectionTestUtils.setField(shiprocketService, "pickupLocation", "Test Pickup");
            ReflectionTestUtils.setField(shiprocketService, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());

            Order order = buildSampleOrder();
            OrderItem item = order.getItems().get(0);
            item.setLineTotal(new java.math.BigDecimal("200.00"));
            item.setAdvanceAllocatedAtCheckout(new java.math.BigDecimal("20.00"));
            item.setCodDueAtCheckout(new java.math.BigDecimal("190.00")); // includes the order's COD charge
            Order.SellerFulfillment fulfillment = new Order.SellerFulfillment();
            fulfillment.setId("ful-partial");
            fulfillment.setItemOrderIds(List.of("item-1"));
            order.setSellerFulfillments(List.of(fulfillment));
            Order.Shipment packageDetails = new Order.Shipment();
            packageDetails.setBookingReference("ORD-1-PKG-1");
            packageDetails.setPackageWeightKg(1.0);
            packageDetails.setLengthCm(20.0);
            packageDetails.setBreadthCm(10.0);
            packageDetails.setHeightCm(10.0);

            ShiprocketOrderResult result = shiprocketService.createFulfillmentShipment(
                    order, "ful-partial", List.of("item-1"), packageDetails);

            assertEquals("AWB-PARTIAL-123", result.awbCode());
            com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().readTree(adhocRequestBody.get());
            assertEquals("COD", payload.path("payment_method").asText());
            assertEquals(0, new java.math.BigDecimal("210.00").compareTo(payload.path("sub_total").decimalValue()));
            assertEquals(0, new java.math.BigDecimal("20.00").compareTo(payload.path("partial_cod_collected").decimalValue()));
            assertEquals(0, new java.math.BigDecimal("190.00").compareTo(payload.path("sub_total").decimalValue()
                    .subtract(payload.path("partial_cod_collected").decimalValue())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("unconfigured non-dev profile does not fabricate serviceability or shipment success")
    void testUnconfiguredProductionFailsClosed() {
        MockEnvironment productionEnvironment = new MockEnvironment();
        productionEnvironment.setActiveProfiles("prod");
        ShiprocketServiceImpl productionService = new ShiprocketServiceImpl(
                productionEnvironment, new ObjectMapper(), redisTemplate);

        ShiprocketServiceabilityResponse serviceability = productionService.checkServiceability("132001", 1.5, false);
        assertFalse(serviceability.serviceable());
        assertEquals("Shiprocket serviceability is not configured", serviceability.message());

        assertThrows(AppException.class,
                () -> productionService.createOrderAndShipment(buildSampleOrder()));
        assertTrue(productionService.trackShipment("AWB-123").contains("UNAVAILABLE"));
    }

    private Order buildSampleOrder() {
        Order order = new Order();
        order.setOrderNumber("S0001-2026-000001");
        order.setPlacedAt(LocalDateTime.now());
        order.setTotalAmount(1940.0);
        order.setPaymentMethod(PaymentMethod.PREPAID);

        OrderAddressSnapshot addr = new OrderAddressSnapshot();
        addr.setName("Kisan Farmer");
        addr.setPhoneNumber("9876543210");
        addr.setPincode("132001");
        addr.setCityTehsil("Karnal");
        addr.setState("Haryana");
        addr.setVillageArea("Sector 12");
        order.setShippingAddress(addr);

        OrderItem i1 = new OrderItem();
        i1.setItemOrderId("item-1");
        Order.ProductSnapshot p1 = new Order.ProductSnapshot();
        p1.setTitle("EBS ACERID-20 Acetamiprid");
        i1.setProductSnapshot(p1);
        i1.setProductCode("PRD-001");
        i1.setVariantSku("SKU-001");
        i1.setQuantity(2);
        i1.setUnitPrice(java.math.BigDecimal.valueOf(970.0));

        OrderItem i2 = new OrderItem();
        i2.setItemOrderId("item-2");
        Order.ProductSnapshot p2 = new Order.ProductSnapshot();
        p2.setTitle("EBS Bio-Fertilizer 1kg");
        i2.setProductSnapshot(p2);
        i2.setProductCode("PRD-002");
        i2.setVariantSku("SKU-002");
        i2.setQuantity(1);
        i2.setUnitPrice(java.math.BigDecimal.valueOf(500.0));

        order.setItems(List.of(i1, i2));
        return order;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
