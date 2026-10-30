package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.AdminManagedUserCreateRequest;
import com.ebs.biocrop.dto.request.AdminManagedUserUpdateRequest;
import com.ebs.biocrop.entity.Address;
import com.ebs.biocrop.entity.Cart;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.Review;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.Wishlist;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.service.impl.AdminUserServiceImpl;
import com.ebs.biocrop.websocket.WebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserManagementServiceTest {
    @Mock private MongoTemplate mongoTemplate;
    @Mock private UserRepository users;
    @Mock private RedisJsonCache redisCache;
    @Mock private WebSocketService webSocketService;
    private AdminUserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminUserServiceImpl(mongoTemplate, users, redisCache, webSocketService);
    }

    @Test
    void createsCustomerWithCustomerRoleAndUnverifiedState() {
        AdminManagedUserCreateRequest request = new AdminManagedUserCreateRequest();
        request.setPhoneNumber("9876543210");
        when(users.existsByPhoneNumber("9876543210")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("customer-id");
            return user;
        });

        var response = service.createManagedUser("CUSTOMER", request);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertEquals(UserRole.ROLE_CUSTOMER, saved.getValue().getRole());
        assertFalse(saved.getValue().getIsVerified());
        assertEquals("ACTIVE", saved.getValue().getStatus());
        assertEquals("customer-id", response.id());
        assertNull(response.shopName());
    }

    @Test
    void sellerDocumentsQualifyForReviewButDoNotAutomaticallyApproveKyc() {
        AdminManagedUserCreateRequest request = new AdminManagedUserCreateRequest();
        request.setPhoneNumber("9876543211");
        request.setEmail("SELLER@example.com");
        request.setFirstName("Ramesh");
        request.setLastName("Patel");
        request.setShopName("Farm Store");
        request.setAddress(completeAddress());
        request.setGstNumber("GST-123");
        request.setLicenceImage("https://storage.example/licence.pdf");
        request.setShopImage("https://storage.example/shop.jpg");
        request.setBankDetails(Map.of("accountHolderName", "Krishi Seva Kendra LLP", "bankName", "State Bank of India"));
        request.setSettlementTerms(Map.of("commissionPercentage", 5, "settlementCycle", "MONTHLY"));
        when(users.existsByPhoneNumber("9876543211")).thenReturn(false);
        when(users.findByEmailIgnoreCase("seller@example.com")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("seller-id");
            return user;
        });

        var response = service.createManagedUser("SELLER", request);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        User seller = saved.getValue();
        assertEquals(UserRole.ROLE_SELLER, seller.getRole());
        assertEquals("seller@example.com", seller.getEmail());
        assertEquals(0L, seller.getProductCodeSequence());
        assertEquals(0L, seller.getOrderNumberSequence());
        assertTrue(seller.hasRequiredSellerKycInformation());
        assertFalse(seller.getIsKycComplete());
        assertNull(seller.getKycStatus());
        assertEquals("Krishi Seva Kendra LLP", seller.getBankDetails().get("accountHolderName"));
        assertEquals("State Bank of India", seller.getBankDetails().get("bankName"));
        assertTrue(seller.getBankDetails().containsKey("accountNumber"));
        assertFalse((Boolean) seller.getBankDetails().get("isVerified"));
        assertEquals(5, seller.getSettlementTerms().get("commissionPercentage"));
        assertEquals("MONTHLY", seller.getSettlementTerms().get("settlementCycle"));
        assertTrue(seller.getSettlementTerms().containsKey("payoutStatus"));
        assertTrue(seller.getIsProfileComplete());
        assertEquals("seller-id", response.id());
    }

    @Test
    void changingSellerKycDocumentsClearsPreviousApproval() {
        User seller = User.newSeller("9876543215", null);
        seller.setGstNumber("GST-123");
        seller.setLicenceImage("https://storage.example/licence.pdf");
        seller.setShopImage("https://storage.example/shop.jpg");
        seller.setKycStatus("APPROVED");
        assertTrue(seller.getIsKycComplete());

        seller.setLicenceImage("https://storage.example/new-licence.pdf");

        assertNull(seller.getKycStatus());
        assertFalse(seller.getIsKycComplete());
        assertTrue(seller.hasRequiredSellerKycInformation());
    }

    @Test
    void sellerCannotBeMarkedKycApprovedWithoutRequiredDocuments() {
        User seller = User.newSeller("9876543216", null);

        seller.setKycStatus("APPROVED");

        assertNull(seller.getKycStatus());
        assertFalse(seller.getIsKycComplete());
    }

    @Test
    void adminKycApprovalRequiresAllDocumentsAndCanApproveAfterReview() {
        User seller = User.newSeller("9876543217", null);
        seller.setId("seller-id");
        when(users.findById("seller-id")).thenReturn(Optional.of(seller));

        AppException missingDocuments = assertThrows(AppException.class,
                () -> service.reviewSellerKyc("seller-id", true));
        assertEquals(HttpStatus.CONFLICT, missingDocuments.getStatus());
        verify(users, never()).save(any(User.class));

        seller.setGstNumber("GST-123");
        seller.setLicenceImage("https://storage.example/licence.pdf");
        seller.setShopImage("https://storage.example/shop.jpg");
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var approved = service.reviewSellerKyc("seller-id", true);

        assertEquals("APPROVED", approved.kycStatus());
        assertTrue(approved.isKycComplete());
        verify(webSocketService).sendToUser(eq("seller-id"), eq("users:kyc_updated"), any());
    }

    @Test
    void kycReviewRejectsNonSellerAccounts() {
        User customer = User.newCustomer("9876543218");
        customer.setId("customer-id");
        when(users.findById("customer-id")).thenReturn(Optional.of(customer));

        AppException error = assertThrows(AppException.class,
                () -> service.reviewSellerKyc("customer-id", true));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(users, never()).save(any(User.class));
    }

    @Test
    void refusesPubliclyRequestedAdminCreation() {
        AdminManagedUserCreateRequest request = new AdminManagedUserCreateRequest();
        request.setPhoneNumber("9876543212");

        AppException error = assertThrows(AppException.class,
                () -> service.createManagedUser("ADMIN", request));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(users, never()).save(any(User.class));
    }

    @Test
    void updatesOnlyCustomerSellerProfileFieldsAndRecomputesCompletion() {
        User customer = User.newCustomer("9876543213");
        customer.setId("customer-id");
        AdminManagedUserUpdateRequest request = new AdminManagedUserUpdateRequest();
        request.setFirstName("Aashutosh");
        request.setLastName("Patel");
        request.setAddress(completeAddress());
        when(users.findById("customer-id")).thenReturn(Optional.of(customer));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateManagedUser("customer-id", request);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertEquals(UserRole.ROLE_CUSTOMER, saved.getValue().getRole());
        assertTrue(saved.getValue().getIsProfileComplete());
        assertTrue(response.isProfileComplete());
    }

    @Test
    void changingSellerShopNameSynchronizesProductSellerNameAndInvalidatesCatalogCaches() {
        User seller = User.newSeller("9876543214", null);
        seller.setId("seller-id");
        seller.setShopName("Old Shop");
        AdminManagedUserUpdateRequest request = new AdminManagedUserUpdateRequest();
        request.setShopName("New Shop");
        when(users.findById("seller-id")).thenReturn(Optional.of(seller));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateManagedUser("seller-id", request);

        verify(mongoTemplate).updateMulti(any(org.springframework.data.mongodb.core.query.Query.class),
                any(org.springframework.data.mongodb.core.query.Update.class), eq("products"));
        verify(redisCache).invalidateRegion("public-products");
        verify(redisCache).invalidateRegion("public-collections");
    }

    @Test
    void blockingSellerDeactivatesListingsAndRevokesSessions() {
        User seller = User.newSeller("9876543220", null);
        seller.setId("seller-id");
        when(users.findById("seller-id")).thenReturn(Optional.of(seller));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.setBlockedStatus("seller-id", true);

        assertTrue(response.isBlocked());
        verify(mongoTemplate).updateMulti(any(), any(Update.class), eq("products"));
        verify(redisCache).invalidateRegion("public-products");
        verify(redisCache).invalidateRegion("public-collections");
        verify(webSocketService).sendToUser(eq("seller-id"), eq("auth:session_revoked"), any());
    }

    @Test
    void adminSchemaDoesNotAcceptCustomerOrSellerBlockAndDeletionFlags() {
        User admin = User.newAdmin("9876543000", "admin@example.com");
        admin.setId("admin-id");
        when(users.findById("admin-id")).thenReturn(Optional.of(admin));

        assertThrows(AppException.class, () -> service.setBlockedStatus("admin-id", true));
        assertThrows(AppException.class, () -> service.setDeletedStatus("admin-id", true));
        assertThrows(AppException.class, () -> service.permanentlyDelete("other-admin", "admin-id"));

        verify(users, never()).save(any(User.class));
        verify(users, never()).delete(any(User.class));
    }

    @Test
    void unblockingSellerDoesNotReactivateListings() {
        User seller = User.newSeller("9876543221", null);
        seller.setId("seller-id");
        seller.setIsBlocked(true);
        when(users.findById("seller-id")).thenReturn(Optional.of(seller));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.setBlockedStatus("seller-id", false);

        assertFalse(response.isBlocked());
        verify(mongoTemplate, never()).updateMulti(any(), any(Update.class), anyString());
        verifyNoInteractions(redisCache);
    }

    @Test
    void softDeletingSellerDeactivatesListingsAndRestoringDoesNotReactivateThem() {
        User seller = User.newSeller("9876543222", null);
        seller.setId("seller-id");
        when(users.findById("seller-id")).thenReturn(Optional.of(seller));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var deleted = service.setDeletedStatus("seller-id", true);

        assertTrue(deleted.isDeleted());
        verify(mongoTemplate).updateMulti(any(), any(Update.class), eq("products"));
        verify(webSocketService).sendToUser(eq("seller-id"), eq("auth:session_revoked"), any());

        clearInvocations(mongoTemplate, redisCache, webSocketService, users);
        seller.setIsDeleted(true);
        when(users.findById("seller-id")).thenReturn(Optional.of(seller));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var restored = service.setDeletedStatus("seller-id", false);

        assertFalse(restored.isDeleted());
        verify(mongoTemplate, never()).updateMulti(any(), any(Update.class), anyString());
        verifyNoInteractions(redisCache);
    }

    @Test
    void permanentDeletionRequiresSoftDeleteAndHasNoOrderOrProductReferences() {
        User seller = User.newSeller("9876543223", null);
        seller.setId("seller-id");
        when(users.findById("seller-id")).thenReturn(Optional.of(seller));

        AppException activeAccount = assertThrows(AppException.class,
                () -> service.permanentlyDelete("admin-id", "seller-id"));

        assertEquals(HttpStatus.CONFLICT, activeAccount.getStatus());
        verify(users, never()).delete(any(User.class));

        seller.setIsDeleted(true);
        when(mongoTemplate.exists(any(org.springframework.data.mongodb.core.query.Query.class), eq(Order.class)))
                .thenReturn(false);
        when(mongoTemplate.exists(any(org.springframework.data.mongodb.core.query.Query.class), eq(Product.class)))
                .thenReturn(false);

        service.permanentlyDelete("admin-id", "seller-id");

        verify(users).delete(seller);
        verify(mongoTemplate).remove(any(org.springframework.data.mongodb.core.query.Query.class), eq(Cart.class));
        verify(mongoTemplate).remove(any(org.springframework.data.mongodb.core.query.Query.class), eq(Wishlist.class));
        verify(webSocketService).sendToUser(eq("seller-id"), eq("auth:session_revoked"), any());
    }

    @Test
    void roleChangeIsRejectedToPreserveRoleSpecificRelationships() {
        User seller = User.newSeller("9876543225", "seller@example.com");
        seller.setId("seller-id");
        when(users.findById("seller-id")).thenReturn(Optional.of(seller));

        AppException error = assertThrows(AppException.class,
                () -> service.assignRole("seller-id", "ROLE_CUSTOMER"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(users, never()).save(any(User.class));
        verify(mongoTemplate, never()).updateMulti(any(), any(Update.class), anyString());
    }

    @Test
    void sellerDashboardReturnsRoleScopedOperationalCounts() {
        User seller = User.newSeller("9876543226", "seller@example.com");
        seller.setId("seller-id");
        when(users.findById("seller-id")).thenReturn(Optional.of(seller));
        when(mongoTemplate.count(any(), eq(Product.class))).thenReturn(12L, 8L);
        when(mongoTemplate.count(any(), eq(Order.class))).thenReturn(5L);
        when(mongoTemplate.count(any(), eq(Review.class))).thenReturn(3L);

        var dashboard = service.getSellerDashboard("seller-id");

        assertEquals(12L, dashboard.totalProducts());
        assertEquals(8L, dashboard.activeProducts());
        assertEquals(5L, dashboard.totalOrders());
        assertEquals(3L, dashboard.totalReviews());
        assertEquals("seller-id", dashboard.seller().id());
    }

    @Test
    void sellerDashboardRejectsNonSellerAccounts() {
        User customer = User.newCustomer("9876543227");
        customer.setId("customer-id");
        when(users.findById("customer-id")).thenReturn(Optional.of(customer));

        AppException error = assertThrows(AppException.class,
                () -> service.getSellerDashboard("customer-id"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("Seller dashboard is only available for seller accounts", error.getMessage());
    }

    @Test
    void permanentDeletionAllowedWhenOrdersExist() {
        User customer = User.newCustomer("9876543224");
        customer.setId("customer-id");
        customer.setIsDeleted(true);
        when(users.findById("customer-id")).thenReturn(Optional.of(customer));
        when(mongoTemplate.count(any(), eq(Order.class))).thenReturn(3L);

        var response = service.permanentlyDelete("admin-id", "customer-id");

        verify(users).delete(customer);
        assertEquals(1, response.count());
    }

    @Test
    void permanentDeletionAllowedWhenSellerHasProducts() {
        User seller = User.newSeller("9876543228", "seller2@example.com");
        seller.setId("seller-with-products");
        seller.setIsDeleted(true);
        when(users.findById("seller-with-products")).thenReturn(Optional.of(seller));
        when(mongoTemplate.count(any(), eq(Product.class))).thenReturn(5L);

        var response = service.permanentlyDelete("admin-id", "seller-with-products");

        verify(users).delete(seller);
        assertEquals(1, response.count());
    }

    @Test
    void batchSoftDeleteAndPermanentDeleteSucceed() {
        User c1 = User.newCustomer("9876543229");
        c1.setId("c1");
        c1.setIsDeleted(false);

        User c2 = User.newCustomer("9876543230");
        c2.setId("c2");
        c2.setIsDeleted(false);

        when(users.findById("c1")).thenReturn(Optional.of(c1));
        when(users.findById("c2")).thenReturn(Optional.of(c2));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var softDeleteResp = service.setDeletedStatusMany(List.of("c1", "c2"), true);
        assertEquals(2, softDeleteResp.size());

        c1.setIsDeleted(true);
        c2.setIsDeleted(true);

        var permDeleteResp = service.permanentlyDeleteMany("admin-id", List.of("c1", "c2"));
        assertEquals(2, permDeleteResp.count());
        verify(users).delete(c1);
        verify(users).delete(c2);
    }

    private Address completeAddress() {
        Address address = new Address();
        address.setVillageArea("Main Road");
        address.setAddress2("Near Market");
        address.setCityTehsil("Anand");
        address.setState("Gujarat");
        address.setPincode("388001");
        return address;
    }
}
