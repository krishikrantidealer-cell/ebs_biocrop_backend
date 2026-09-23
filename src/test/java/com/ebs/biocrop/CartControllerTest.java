package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.CartItemRequest;
import com.ebs.biocrop.dto.request.CartItemUpdateRequest;
import com.ebs.biocrop.dto.request.CartSyncRequest;
import com.ebs.biocrop.dto.response.CartItemResponse;
import com.ebs.biocrop.dto.response.CartResponse;
import com.ebs.biocrop.dto.response.CheckoutSummaryResponse;
import com.ebs.biocrop.entity.Address;
import com.ebs.biocrop.repository.CartRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.CartService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CartService cartService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private CartRepository cartRepository;

    private CartResponse createSampleCartResponse() {
        CartItemResponse item = new CartItemResponse(
                "prod123", "VAR-001", "Bio Fertilizer",
                "KG", 1, 500.0, 450.0, 40.0,
                2, 900.0, 10, true
        );
        return new CartResponse(
                "cart123", "user123", "9876543210",
                List.of(item), 1, 2, 1000.0,
                100.0, 900.0, 40.0, 940.0,
                LocalDateTime.now()
        );
    }

    @Test
    void getCartWithoutAuthShouldBeUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "9876543210")
    void getCartWithAuthShouldReturnCart() throws Exception {
        when(cartService.getCart("9876543210")).thenReturn(createSampleCartResponse());

        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.phoneNumber").value("9876543210"))
                .andExpect(jsonPath("$.data.items[0].variationCode").value("VAR-001"))
                .andExpect(jsonPath("$.data.totalSalePrice").value(900.0))
                .andExpect(jsonPath("$.data.finalAmount").value(940.0));
    }

    @Test
    @WithMockUser(username = "9876543210")
    void getCartCountWithAuthShouldReturnCounts() throws Exception {
        when(cartService.getCartCount("9876543210"))
                .thenReturn(new com.ebs.biocrop.dto.response.CartCountResponse(2, 5));

        mockMvc.perform(get("/api/v1/cart/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.itemCount").value(2))
                .andExpect(jsonPath("$.data.totalQuantity").value(5));
    }

    @Test
    @WithMockUser(username = "9876543210")
    void addToCartShouldSucceed() throws Exception {
        when(cartService.addToCart(eq("9876543210"), any())).thenReturn(createSampleCartResponse());

        CartItemRequest req = new CartItemRequest("VAR-001", null, 2);

        mockMvc.perform(post("/api/v1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));
    }

    @Test
    @WithMockUser(username = "9876543210")
    void updateQuantityShouldSucceed() throws Exception {
        when(cartService.updateQuantity(eq("9876543210"), eq("VAR-001"), eq(3)))
                .thenReturn(createSampleCartResponse());

        CartItemUpdateRequest req = new CartItemUpdateRequest(3);

        mockMvc.perform(put("/api/v1/cart/items/VAR-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(username = "9876543210")
    void removeItemShouldSucceed() throws Exception {
        CartResponse emptyCart = new CartResponse(
                "cart123", "user123", "9876543210",
                Collections.emptyList(), 0, 0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                LocalDateTime.now()
        );
        when(cartService.removeItem("9876543210", "VAR-001")).thenReturn(emptyCart);

        mockMvc.perform(delete("/api/v1/cart/items/VAR-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.itemCount").value(0));
    }

    @Test
    @WithMockUser(username = "9876543210")
    void clearCartShouldSucceed() throws Exception {
        CartResponse emptyCart = new CartResponse(
                "cart123", "user123", "9876543210",
                Collections.emptyList(), 0, 0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                LocalDateTime.now()
        );
        when(cartService.clearCart("9876543210")).thenReturn(emptyCart);

        mockMvc.perform(delete("/api/v1/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.itemCount").value(0));
    }

    @Test
    @WithMockUser(username = "9876543210")
    void syncCartShouldSucceed() throws Exception {
        when(cartService.syncCart(eq("9876543210"), any())).thenReturn(createSampleCartResponse());

        CartSyncRequest syncReq = new CartSyncRequest(List.of(new CartItemRequest("VAR-001", null, 2)));

        mockMvc.perform(post("/api/v1/cart/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(syncReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(username = "9876543210")
    void checkoutSummaryShouldReturnReadiness() throws Exception {
        Address addr = new Address("Flat 101", "MG Road", "Indore", "MP", "452001");
        CheckoutSummaryResponse summary = new CheckoutSummaryResponse(
                createSampleCartResponse(), true, Collections.emptyList(),
                addr, 900.0, 100.0, 40.0, 940.0
        );
        when(cartService.getCheckoutSummary("9876543210")).thenReturn(summary);

        mockMvc.perform(get("/api/v1/cart/checkout-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.readyForCheckout").value(true))
                .andExpect(jsonPath("$.data.finalPayableAmount").value(940.0))
                .andExpect(jsonPath("$.data.deliveryAddress.city").value("Indore"));
    }
}
