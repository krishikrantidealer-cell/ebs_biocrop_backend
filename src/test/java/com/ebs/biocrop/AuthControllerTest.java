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

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        com.ebs.biocrop.entity.Address address = new com.ebs.biocrop.entity.Address("Flat 101", "MG Road", "Indore", "Madhya Pradesh", "452001");
        com.ebs.biocrop.dto.response.UserProfileResponse response = new com.ebs.biocrop.dto.response.UserProfileResponse(
                "id123", "9876543210", "Aashutosh Shrivastava", address, "ROLE_CUSTOMER",
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now()
        );
        when(userService.updateProfile(any(), any())).thenReturn(response);

        Map<String, Object> req = Map.of(
                "fullName", "Aashutosh Shrivastava",
                "address", Map.of(
                        "address_line_1", "Flat 101",
                        "near_by_location", "MG Road",
                        "city", "Indore",
                        "state", "Madhya Pradesh",
                        "pin_code", "452001"
                )
        );

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/user/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fullName").value("Aashutosh Shrivastava"))
                .andExpect(jsonPath("$.data.address.address_line_1").value("Flat 101"))
                .andExpect(jsonPath("$.data.address.near_by_location").value("MG Road"))
                .andExpect(jsonPath("$.data.address.city").value("Indore"))
                .andExpect(jsonPath("$.data.address.state").value("Madhya Pradesh"))
                .andExpect(jsonPath("$.data.address.pin_code").value("452001"));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(username = "9876543210")
    void deleteProfileShouldSoftDeleteAndReturnIsDeleteTrue() throws Exception {
        com.ebs.biocrop.entity.Address address = new com.ebs.biocrop.entity.Address("Flat 101", "MG Road", "Indore", "Madhya Pradesh", "452001");
        com.ebs.biocrop.dto.response.UserProfileResponse response = new com.ebs.biocrop.dto.response.UserProfileResponse(
                "id123", "9876543210", "Aashutosh Shrivastava", address, "ROLE_CUSTOMER", true,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now()
        );
        when(userService.softDeleteUserProfile("9876543210")).thenReturn(response);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/user/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User profile deleted successfully"))
                .andExpect(jsonPath("$.data.is_delete").value(true));
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
}
