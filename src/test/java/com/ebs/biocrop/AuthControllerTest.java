package com.ebs.biocrop;

import com.ebs.biocrop.dto.response.OtpResponse;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.AuthService;
import com.ebs.biocrop.service.OtpService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private OtpService otpService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private com.ebs.biocrop.service.UserService userService;

    @MockitoBean
    private com.ebs.biocrop.service.RateLimitService rateLimitService;

    @Test
    void healthCheckShouldReturnSuccess() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.database").value("MongoDB Atlas (seller_hub)"));
    }

    @Test
    void sendOtpShouldSucceedForValidPhoneNumber() throws Exception {
        when(otpService.generateAndSendOtp(any())).thenReturn(
                new OtpResponse("98******10", "OTP_SENT", 60, 5, "OTP dispatched successfully")
        );

        Map<String, String> request = Map.of("phoneNumber", "9876543210");

        mockMvc.perform(post("/api/v1/auth/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("OTP_SENT"));
    }

    @Test
    void profileEndpointWithoutJwtShouldBeUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/user/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(username = "9876543210")
    void updateProfileWithStructuredAddressShouldSucceed() throws Exception {
        com.ebs.biocrop.entity.Address address = new com.ebs.biocrop.entity.Address("Flat 101", "Indore", "Madhya Pradesh", "452001");
        address.setAddress2("MG Road");
        com.ebs.biocrop.dto.response.UserProfileResponse response = new com.ebs.biocrop.dto.response.UserProfileResponse(
                "id123", "9876543210", "Aashutosh", "Shrivastava", address, "ROLE_CUSTOMER",
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now()
        );
        when(userService.updateProfile(any(), any())).thenReturn(response);

        Map<String, Object> req = Map.of(
                "firstName", "Aashutosh",
                "lastName", "Shrivastava",
                "address", Map.of(
                        "villageArea", "Flat 101",
                        "address2", "MG Road",
                        "cityTehsil", "Indore",
                        "state", "Madhya Pradesh",
                        "pincode", "452001"
                )
        );

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/user/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.firstName").value("Aashutosh"))
                .andExpect(jsonPath("$.data.address.villageArea").value("Flat 101"))
                .andExpect(jsonPath("$.data.address.address2").value("MG Road"))
                .andExpect(jsonPath("$.data.address.cityTehsil").value("Indore"))
                .andExpect(jsonPath("$.data.address.state").value("Madhya Pradesh"))
                .andExpect(jsonPath("$.data.address.pincode").value("452001"));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(username = "9876543210")
    void deleteProfileShouldSoftDeleteAndReturnIsDeleteTrue() throws Exception {
        com.ebs.biocrop.entity.Address address = new com.ebs.biocrop.entity.Address("Flat 101", "Indore", "Madhya Pradesh", "452001");
        address.setAddress2("MG Road");
        com.ebs.biocrop.dto.response.UserProfileResponse response = new com.ebs.biocrop.dto.response.UserProfileResponse(
                "id123", "9876543210", "Aashutosh", "Shrivastava", address, "ROLE_CUSTOMER", true,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now()
        );
        when(userService.softDeleteUserProfile("9876543210")).thenReturn(response);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/user/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User profile deleted successfully"))
                .andExpect(jsonPath("$.data.isDeleted").value(true));
    }

    @Test
    void verifyOtpShouldReturnTokenAndRole() throws Exception {
        com.ebs.biocrop.dto.response.AuthResponse authResponse = new com.ebs.biocrop.dto.response.AuthResponse(
                "mock-access-token", "mock-refresh-token", 86400000L, "id123", "9876543210", "ROLE_SELLER"
        );
        when(authService.verifyOtpAndLogin(any())).thenReturn(authResponse);

        Map<String, String> req = Map.of(
                "phoneNumber", "9876543210",
                "otp", "123456",
                "role", "ROLE_SELLER"
        );

        mockMvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("mock-access-token"))
                .andExpect(jsonPath("$.data.role").value("ROLE_SELLER"));
    }

    @Test
    void refreshTokenShouldApplyTenPerMinuteIpLimit() throws Exception {
        String clientIp = "192.0.2.51";
        when(authService.refreshAccessToken("mock-refresh-token")).thenReturn(
                new com.ebs.biocrop.dto.response.AuthResponse(
                        "new-access-token", "mock-refresh-token", 86400000L,
                        "id123", "9876543210", "ROLE_CUSTOMER"
                )
        );

        mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", "mock-refresh-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(rateLimitService).check("token-refresh-ip", clientIp, 10, Duration.ofSeconds(60));
    }

    @Test
    void refreshTokenShouldReturn429WhenIpLimitIsExceeded() throws Exception {
        String clientIp = "192.0.2.52";
        when(rateLimitService.check("token-refresh-ip", clientIp, 10, Duration.ofSeconds(60)))
                .thenReturn(60L);

        mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", "mock-refresh-token"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.status").value(429));

        verify(authService, never()).refreshAccessToken(any());
    }
}
