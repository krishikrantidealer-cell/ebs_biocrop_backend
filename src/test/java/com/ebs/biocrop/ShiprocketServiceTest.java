package com.ebs.biocrop;

import com.ebs.biocrop.dto.response.ShiprocketOrderResult;
import com.ebs.biocrop.dto.response.ShiprocketServiceabilityResponse;
import com.ebs.biocrop.entity.*;
import com.ebs.biocrop.service.impl.ShiprocketServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDateTime;
import java.util.List;

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
        Order order = new Order();
        order.setOrderNumber("S0001-2026-000001");
        order.setPlacedAt(LocalDateTime.now());
        order.setTotalAmount(1940.0);
        order.setPaymentMethod(OrderPaymentMethod.PREPAID);

        OrderAddressSnapshot addr = new OrderAddressSnapshot();
        addr.setName("Kisan Farmer");
        addr.setPhoneNumber("9876543210");
        addr.setPincode("132001");
        addr.setCityTehsil("Karnal");
        addr.setState("Haryana");
        addr.setVillageArea("Sector 12");
        order.setShippingAddress(addr);

        OrderItem item = new OrderItem();
        item.setTitle("EBS ACERID-20 Acetamiprid");
        item.setProductCode("PRD-001");
        item.setSku("SKU-001");
        item.setQuantity(2);
        item.setUnitPrice(970.0);
        order.setItems(List.of(item));

        ShiprocketOrderResult result = shiprocketService.createOrderAndShipment(order);
        assertNotNull(result);
        assertNotNull(result.shiprocketOrderId());
        assertNotNull(result.shipmentId());
        assertNotNull(result.awbCode());
        assertNotNull(result.courierName());
        assertTrue(result.trackingUrl().contains("shiprocket.co/tracking/"));
    }
}
