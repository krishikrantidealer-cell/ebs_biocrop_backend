package com.ebs.biocrop;

import com.ebs.biocrop.dto.response.AdminSellerDashboardResponse;
import com.ebs.biocrop.dto.response.AdminUserResponse;
import com.ebs.biocrop.dto.response.PermanentDeleteUsersResponse;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.exception.AppException;
import org.springframework.http.HttpStatus;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.AdminUserService;
import com.ebs.biocrop.service.RateLimitGuard;
import com.ebs.biocrop.service.RateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.database.maintenance-mode.enabled=true",
        "app.database.legacy-migration.enabled=false",
        "app.database.category-migration.enabled=false"
})
@AutoConfigureMockMvc
class AdminUserControllerTest {
    @Autowired private MockMvc mockMvc;

    @MockitoBean private AdminUserService adminUserService;
    @MockitoBean private RateLimitGuard rateLimitGuard;
    @MockitoBean private RateLimitService rateLimitService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProductRepository productRepository;

    @Test
    void adminCanListSellersAndCreateCustomer() throws Exception {
        AdminUserResponse seller = response("seller-id", "ROLE_SELLER", false, false);
        when(adminUserService.list(eq("SELLER"), isNull(), isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(seller)));
        when(adminUserService.createManagedUser(eq("CUSTOMER"), any()))
                .thenReturn(response("customer-id", "ROLE_CUSTOMER", false, false));

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("role", "SELLER")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].role").value("ROLE_SELLER"))
                .andExpect(jsonPath("$.data.content[0].productCodeSequence").value(0))
                .andExpect(jsonPath("$.data.content[0].orderNumberSequence").value(0))
                .andExpect(jsonPath("$.data.content[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].passwordResetTokenHash").doesNotExist());

        mockMvc.perform(post("/api/v1/admin/users/customers")
                        .with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"9876543210\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("ROLE_CUSTOMER"));

        verify(adminUserService).createManagedUser(eq("CUSTOMER"), any());
    }

    @Test
    void sellerCannotAccessAdminUserEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").with(user(seller())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/users/sellers")
                        .with(user(seller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"9876543211\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminUserService);
    }

    @Test
    void adminCanBlockRestoreAndPermanentlyDeleteThroughLifecycleEndpoints() throws Exception {
        when(adminUserService.setBlockedStatus("seller-id", true))
                .thenReturn(response("seller-id", "ROLE_SELLER", true, false));
        when(adminUserService.setDeletedStatus("seller-id", true))
                .thenReturn(response("seller-id", "ROLE_SELLER", false, true));
        when(adminUserService.setDeletedStatus("seller-id", false))
                .thenReturn(response("seller-id", "ROLE_SELLER", false, false));
        when(adminUserService.reviewSellerKyc("seller-id", true))
                .thenReturn(new AdminUserResponse("seller-id", "9876543210", "seller@example.com", "First", "Last",
                        "Shop", "ROLE_SELLER", null, true, false, "APPROVED", true, "GST-123",
                        "licence.pdf", "shop.jpg", List.of(), false, false, null, null, "ACTIVE", null,
                        List.of(), null, null, 0L, 0L));

        mockMvc.perform(patch("/api/v1/admin/users/seller-id/block")
                        .with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isBlocked").value(true));

        mockMvc.perform(patch("/api/v1/admin/users/seller-id/deletion")
                        .with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deleted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDeleted").value(true));

        mockMvc.perform(patch("/api/v1/admin/users/seller-id/deletion")
                        .with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deleted\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDeleted").value(false));

        mockMvc.perform(patch("/api/v1/admin/users/seller-id/kyc")
                        .with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kycStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.isKycComplete").value(true));

        when(adminUserService.permanentlyDelete("admin-id", "seller-id"))
                .thenReturn(PermanentDeleteUsersResponse.of(List.of("seller-id")));

        mockMvc.perform(delete("/api/v1/admin/users/seller-id/permanent")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(adminUserService).setBlockedStatus("seller-id", true);
        verify(adminUserService).setDeletedStatus("seller-id", true);
        verify(adminUserService).setDeletedStatus("seller-id", false);
        verify(adminUserService).reviewSellerKyc("seller-id", true);
        verify(adminUserService).permanentlyDelete(anyString(), eq("seller-id"));
    }

    @Test
    void adminCanPerformBatchSoftDeleteAndBatchPermanentDelete() throws Exception {
        when(adminUserService.setDeletedStatusMany(eq(List.of("u1", "u2")), eq(true)))
                .thenReturn(List.of(
                        response("u1", "ROLE_CUSTOMER", false, true),
                        response("u2", "ROLE_CUSTOMER", false, true)
                ));

        mockMvc.perform(patch("/api/v1/admin/users/deletion")
                        .with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[\"u1\",\"u2\"],\"deleted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.message").value("Users deactivated"));

        when(adminUserService.permanentlyDeleteMany(anyString(), eq(List.of("u1", "u2"))))
                .thenReturn(PermanentDeleteUsersResponse.of(List.of("u1", "u2")));

        mockMvc.perform(delete("/api/v1/admin/users/permanent")
                        .with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[\"u1\",\"u2\"],\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.deletedUserIds[0]").value("u1"))
                .andExpect(jsonPath("$.message").value("Users permanently deleted"));
    }

    @Test
    void adminEndpointsRequireAuthenticationAndLifecyclePayload() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/admin/users/seller-id/block")
                        .with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/admin/users/seller-id/kyc")
                        .with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanGetSellerDashboardAndRejectsNonSeller() throws Exception {
        AdminUserResponse seller = response("seller-id", "ROLE_SELLER", false, false);
        AdminSellerDashboardResponse dashboard = new AdminSellerDashboardResponse(
                seller, 10L, 8L, 4L, 2L);
        when(adminUserService.getSellerDashboard("seller-id")).thenReturn(dashboard);
        when(adminUserService.getSellerDashboard("customer-id"))
                .thenThrow(new AppException("Seller dashboard is only available for seller accounts", HttpStatus.CONFLICT));

        mockMvc.perform(get("/api/v1/admin/users/seller-id/dashboard")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalProducts").value(10))
                .andExpect(jsonPath("$.data.activeProducts").value(8))
                .andExpect(jsonPath("$.data.totalOrders").value(4))
                .andExpect(jsonPath("$.data.totalReviews").value(2))
                .andExpect(jsonPath("$.data.seller.id").value("seller-id"));

        mockMvc.perform(get("/api/v1/admin/users/customer-id/dashboard")
                        .with(user(admin())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Seller dashboard is only available for seller accounts"));
    }

    @Test
    void adminRoleChangeReturns409WhenChangingRoleAnd200WhenConfirmingSameRole() throws Exception {
        when(adminUserService.assignRole("user-1", "ROLE_CUSTOMER"))
                .thenReturn(com.ebs.biocrop.entity.enums.UserRole.ROLE_CUSTOMER);
        when(adminUserService.assignRole("user-1", "ROLE_SELLER"))
                .thenThrow(new AppException("Role changes are not permitted after account creation; create a new account instead.", HttpStatus.CONFLICT));

        mockMvc.perform(patch("/api/v1/admin/users/user-1/role")
                        .with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ROLE_CUSTOMER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User role confirmed"))
                .andExpect(jsonPath("$.data.role").value("ROLE_CUSTOMER"));

        mockMvc.perform(patch("/api/v1/admin/users/user-1/role")
                        .with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ROLE_SELLER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Role changes are not permitted after account creation; create a new account instead."));
    }

    private CustomUserDetails admin() {
        User user = User.newAdmin("9876543000", "admin@example.com");
        user.setId("admin-id");
        return CustomUserDetails.build(user);
    }

    private CustomUserDetails seller() {
        User user = User.newSeller("9876543001", "seller@example.com");
        user.setId("seller-id");
        return CustomUserDetails.build(user);
    }

    private AdminUserResponse response(String id, String role, boolean blocked, boolean deleted) {
        return new AdminUserResponse(id, "9876543210", "user@example.com", "First", "Last",
                role.equals("ROLE_SELLER") ? "Shop" : null, role, null, true, false,
                null, false, null, null, null, List.of(), blocked, deleted,
                null, null, "ACTIVE", null, List.of(), null, null,
                role.equals("ROLE_SELLER") ? 0L : null, role.equals("ROLE_SELLER") ? 0L : null);
    }
}
