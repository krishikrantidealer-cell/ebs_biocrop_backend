package com.ebs.biocrop;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.config.UserRoleSchemaWriteCallback;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import static org.junit.jupiter.api.Assertions.*;

class UserRoleSchemaPersistenceTest {
    private MappingMongoConverter converter() throws Exception {
        MongoCustomConversions conversions = MongoCustomConversions.create(adapter -> { });
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return converter;
    }

    @Test
    void allRolesUseUsersCollectionAndPersistRoleSpecificSellerFieldsOnlyForSellers() throws Exception {
        assertEquals("users", User.class.getAnnotation(org.springframework.data.mongodb.core.mapping.Document.class).collection());
        MappingMongoConverter converter = converter();

        User customer = User.newCustomer("9876543210");
        Document customerDocument = new Document();
        converter.write(customer, customerDocument);
        new UserRoleSchemaWriteCallback().onBeforeSave(customer, customerDocument, "users");
        assertEquals(UserRole.ROLE_CUSTOMER.name(), customerDocument.getString("role"));
        assertEquals("ACTIVE", customerDocument.getString("status"));
        assertFalse(customerDocument.containsKey("shopName"));
        assertFalse(customerDocument.containsKey("productCodeSequence"));
        assertFalse(customerDocument.containsKey("orderNumberSequence"));
        assertFalse(customerDocument.containsKey("gstNumber"));
        assertFalse(customerDocument.containsKey("bankDetails"));
        assertFalse(customerDocument.containsKey("settlementTerms"));
        assertTrue(customerDocument.containsKey("email"));
        assertTrue(customerDocument.containsKey("address"));
        assertTrue(customerDocument.containsKey("shippingAddresses"));
        assertFalse(customerDocument.containsKey("passwordHash"));
        assertFalse(customerDocument.containsKey("isKycComplete"));
        assertFalse(customerDocument.containsKey("kycStatus"));
        assertFalse(customerDocument.containsKey("warehouseAddresses"));

        User seller = User.newSeller("9876543211", "seller@example.com");
        seller.setShopName("Farm Shop");
        seller.setPanNumber("AABCU9603R");
        seller.setLicenceNo("LIC-2026-01");
        seller.setVersion(4);
        Document sellerDocument = new Document();
        converter.write(seller, sellerDocument);
        new UserRoleSchemaWriteCallback().onBeforeSave(seller, sellerDocument, "users");
        assertEquals(UserRole.ROLE_SELLER.name(), sellerDocument.getString("role"));
        assertEquals("ACTIVE", sellerDocument.getString("status"));
        assertEquals("Farm Shop", sellerDocument.getString("shopName"));
        assertEquals("AABCU9603R", sellerDocument.getString("panNumber"));
        assertEquals("LIC-2026-01", sellerDocument.getString("licenceNo"));
        assertEquals(0L, sellerDocument.get("productCodeSequence"));
        assertEquals(0L, sellerDocument.get("orderNumberSequence"));
        assertEquals(4, sellerDocument.get("version"));
        assertTrue(sellerDocument.containsKey("bankDetails"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> bankDetails = (java.util.Map<String, Object>) sellerDocument.get("bankDetails");
        assertTrue(bankDetails.containsKey("accountHolderName"));
        assertTrue(bankDetails.containsKey("accountNumber"));
        assertTrue(bankDetails.containsKey("cancelledCheque"));
        assertEquals(Boolean.FALSE, bankDetails.get("isVerified"));
        assertTrue(sellerDocument.containsKey("settlementTerms"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> settlementTerms = (java.util.Map<String, Object>) sellerDocument.get("settlementTerms");
        assertEquals(0, settlementTerms.get("commissionPercentage"));
        assertTrue(settlementTerms.containsKey("payoutStatus"));
        assertTrue(sellerDocument.containsKey("warehouseAddresses"));
        assertTrue(sellerDocument.containsKey("passwordHash"));
        assertFalse(sellerDocument.containsKey("notesHistory"));
        assertFalse(sellerDocument.containsKey("whatsappSequence"));
        assertFalse(sellerDocument.containsKey("isVerified"));
        assertFalse(sellerDocument.containsKey("shippingAddresses"));
        assertFalse(sellerDocument.containsKey("isKycComplete"));
        assertFalse(sellerDocument.containsKey("kycStatus"));
        assertTrue(sellerDocument.containsKey("licenceImage"));
        assertTrue(sellerDocument.containsKey("gstCertificate"));
        assertTrue(sellerDocument.containsKey("panCard"));
        assertTrue(sellerDocument.containsKey("aadhaarCard"));
        assertTrue(sellerDocument.containsKey("agreement"));
        
        User admin = User.newAdmin("9876543212", "admin@example.com");
        Document adminDocument = new Document();
        converter.write(admin, adminDocument);
        new UserRoleSchemaWriteCallback().onBeforeSave(admin, adminDocument, "users");
        assertEquals(UserRole.ROLE_ADMIN.name(), adminDocument.getString("role"));
        assertFalse(adminDocument.containsKey("shopName"));
        assertFalse(adminDocument.containsKey("gstNumber"));
        assertFalse(adminDocument.containsKey("productCodeSequence"));
        assertFalse(adminDocument.containsKey("orderNumberSequence"));
        assertFalse(adminDocument.containsKey("bankDetails"));
        assertFalse(adminDocument.containsKey("settlementTerms"));
        assertFalse(adminDocument.containsKey("isVerified"));
        assertFalse(adminDocument.containsKey("isBlocked"));
        assertFalse(adminDocument.containsKey("isDeleted"));
        assertFalse(adminDocument.containsKey("whatsappSequence"));
        assertEquals(true, adminDocument.getBoolean("isProfileComplete"));
        
    }
}
