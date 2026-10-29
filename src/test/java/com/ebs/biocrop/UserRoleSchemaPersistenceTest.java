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

        User seller = User.newSeller("9876543211", "seller@example.com");
        seller.setShopName("Farm Shop");
        seller.setVersion(4);
        Document sellerDocument = new Document();
        converter.write(seller, sellerDocument);
        new UserRoleSchemaWriteCallback().onBeforeSave(seller, sellerDocument, "users");
        assertEquals(UserRole.ROLE_SELLER.name(), sellerDocument.getString("role"));
        assertEquals("ACTIVE", sellerDocument.getString("status"));
        assertEquals("Farm Shop", sellerDocument.getString("shopName"));
        assertEquals(0L, sellerDocument.get("productCodeSequence"));
        assertEquals(0L, sellerDocument.get("orderNumberSequence"));
        assertEquals(4, sellerDocument.get("version"));
        assertTrue(sellerDocument.containsKey("bankDetails"));
        assertNull(sellerDocument.get("bankDetails"));
        assertTrue(sellerDocument.containsKey("settlementTerms"));
        assertNull(sellerDocument.get("settlementTerms"));
        assertTrue(sellerDocument.containsKey("notesHistory"));
        assertTrue(sellerDocument.containsKey("shippingAddresses"));

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
