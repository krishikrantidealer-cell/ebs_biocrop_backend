package com.ebs.biocrop;

import com.ebs.biocrop.dto.response.RazorpayOrderResult;
import com.ebs.biocrop.service.impl.RazorpayServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RazorpayServiceTest {

    private RazorpayServiceImpl razorpayService;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        razorpayService = new RazorpayServiceImpl(environment, new ObjectMapper());
        ReflectionTestUtils.setField(razorpayService, "keyId", "rzp_test_ebs_biocrop_default");
        ReflectionTestUtils.setField(razorpayService, "keySecret", "ebs_test_secret_12345");
    }

    @Test
    @DisplayName("createOrder returns valid order with paise and rupee amounts")
    void testCreateOrder() {
        RazorpayOrderResult result = razorpayService.createOrder(1250.0, "rcpt_001", "INR", Map.of("userId", "user_123"));
        assertNotNull(result);
        assertEquals(125000L, result.amountPaise());
        assertEquals(1250.0, result.amountRupees());
        assertEquals("INR", result.currency());
        assertEquals("rcpt_001", result.receipt());
        assertNotNull(result.orderId());
    }

    @Test
    @DisplayName("verifyPaymentSignature validates correct HMAC-SHA256 signature")
    void testVerifySignature() throws Exception {
        String orderId = "order_live_987654321";
        String paymentId = "pay_live_123456789";
        String secret = "ebs_test_secret_12345";

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String payload = orderId + "|" + paymentId;
        String validSignature = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));

        assertTrue(razorpayService.verifyPaymentSignature(orderId, paymentId, validSignature));
        assertFalse(razorpayService.verifyPaymentSignature(orderId, paymentId, "invalid_sig_abc"));
    }
}
