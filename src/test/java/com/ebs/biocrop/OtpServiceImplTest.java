package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.OtpSendRequest;
import com.ebs.biocrop.dto.response.OtpResponse;
import com.ebs.biocrop.service.RedisOtpCooldown;
import com.ebs.biocrop.service.RedisOtpStore;
import com.ebs.biocrop.service.SmsDeliveryService;
import com.ebs.biocrop.service.impl.OtpServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtpServiceImplTest {

    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RedisOtpCooldown redisOtpCooldown;
    @Mock private RedisOtpStore redisOtpStore;
    @Mock private SmsDeliveryService smsDeliveryService;

    private MockEnvironment environment;
    private OtpServiceImpl otpService;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        otpService = new OtpServiceImpl(
                passwordEncoder,
                environment,
                redisOtpCooldown,
                redisOtpStore,
                smsDeliveryService
        );

        ReflectionTestUtils.setField(otpService, "consoleDeliveryEnabled", true);
        ReflectionTestUtils.setField(otpService, "masterOtp", "123456");
        ReflectionTestUtils.setField(otpService, "masterOtpEnabled", true);
        ReflectionTestUtils.setField(otpService, "otpLength", 6);
        ReflectionTestUtils.setField(otpService, "expirationMinutes", 5);
        ReflectionTestUtils.setField(otpService, "cooldownSeconds", 60);
        ReflectionTestUtils.setField(otpService, "maxAttempts", 3);
    }

    @Test
    @DisplayName("generateAndSendOtp dispatches OTP via SmsDeliveryService")
    void testGenerateAndSendOtpDispatchesSms() {
        when(redisOtpCooldown.acquire(anyString(), anyString(), any(), anyString())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-otp");
        when(redisOtpStore.save(anyString(), anyString(), anyString(), any())).thenReturn("entry-123");
        when(smsDeliveryService.isConfigured()).thenReturn(true);

        OtpSendRequest request = new OtpSendRequest("9876543210");
        OtpResponse response = otpService.generateAndSendOtp(request);

        assertNotNull(response);
        assertEquals("OTP_SENT", response.getStatus());
        assertEquals(60, response.getCooldownSeconds());

        verify(smsDeliveryService, times(1)).sendOtp(eq("9876543210"), anyString());
        verify(redisOtpStore, times(1)).save(eq("phone"), eq("9876543210"), eq("hashed-otp"), any());
    }

    @Test
    @DisplayName("verifyOtp succeeds immediately with Master OTP")
    void testVerifyMasterOtp() {
        assertDoesNotThrow(() -> otpService.verifyOtp("9876543210", "123456"));
        verifyNoInteractions(redisOtpStore);
    }
}
