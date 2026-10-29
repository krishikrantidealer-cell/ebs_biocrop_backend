package com.ebs.biocrop.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.Criteria;
import org.bson.Document;

import java.util.List;

@Configuration
public class MongoConfig {

    private static final Logger log = LoggerFactory.getLogger(MongoConfig.class);

    private final MappingMongoConverter mappingMongoConverter;
    private final MongoTemplate mongoTemplate;
    @Value("${app.database.legacy-migration.enabled:false}")
    private boolean legacyMigrationEnabled;

    public MongoConfig(MappingMongoConverter mappingMongoConverter, MongoTemplate mongoTemplate) {
        this.mappingMongoConverter = mappingMongoConverter;
        this.mongoTemplate = mongoTemplate;
    }

    @Bean
    public MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }

    @PostConstruct
    public void configureMongoMapping() {
        // Disable writing '_class' on future MongoDB documents.
        mappingMongoConverter.setTypeMapper(new DefaultMongoTypeMapper(null));
        log.info("MongoDB MappingMongoConverter configured: '_class' field mapping disabled.");

        // Legacy schema changes are opt-in so a normal application startup is read-only.
        if (!legacyMigrationEnabled) {
            log.info("Legacy MongoDB migration is disabled.");
            return;
        }

        try {
            Update userSchemaUpdate = new Update()
                    .rename("fullName", "firstName")
                    .rename("address.address_line_1", "address.villageArea")
                    .rename("address.near_by_location", "address.address2")
                    .rename("address.city", "address.cityTehsil")
                    .rename("address.pin_code", "address.pincode")
                    .rename("is_delete", "isDeleted")
                    .unset("source")
                    .unset("assignedAgent")
                    .unset("addressType")
                    .unset("fcmToken")
                    .unset("userType")
                    .unset("kycReminderCount")
                    .unset("assignedAt")
                    .unset("lastMarketingNotificationSentAt")
                    .unset("lastMarketingSegment")
                    .unset("last530PMSentAt")
                    .unset("last8PMSentAt")
                    .unset("lastWinBackSentAt")
                    .unset("lastFirstOrderSentAt")
                    .unset("lastKycReminderSentAt");
            
            mongoTemplate.updateMulti(new Query(), userSchemaUpdate, "users");
            log.info("Successfully migrated 'users' schema (renamed legacy fields).");

            Query sellers = Query.query(Criteria.where("role").is("ROLE_SELLER"));
            mongoTemplate.updateMulti(sellers,
                    new Update().set("bankDetails", null).set("settlementTerms", null), "users");
            Query nonSellers = Query.query(Criteria.where("role").ne("ROLE_SELLER"));
            mongoTemplate.updateMulti(nonSellers,
                    new Update().unset("bankDetails").unset("settlementTerms"), "users");
            Query missingRoleStatus = Query.query(new Criteria().andOperator(
                    Criteria.where("role").in("ROLE_CUSTOMER", "ROLE_SELLER"),
                    new Criteria().orOperator(
                            Criteria.where("status").exists(false),
                            Criteria.where("status").is(null),
                            Criteria.where("status").is(""))));
            mongoTemplate.updateMulti(missingRoleStatus, new Update().set("status", "ACTIVE"), "users");

            migrateAddressObjectField("users", "address");
            migrateAddressArrayField("users", "shippingAddresses");
            migrateAddressObjectField("orders", "shippingAddress");
            migrateAddressObjectField("orders", "billingAddress");
            log.info("Successfully migrated address snapshots to the address2 field.");

        } catch (Exception e) {
            throw new IllegalStateException("Legacy MongoDB migration failed; startup is stopped to avoid using a partially migrated schema.", e);
        }
    }

    private void migrateAddressObjectField(String collection, String field) {
        Document setFields = new Document(field + ".address2",
                new Document("$ifNull", List.of("$" + field + ".address2", "$" + field + ".addressLine2")))
                .append(field + ".addressLine2", "$$REMOVE");
        mongoTemplate.getCollection(collection).updateMany(
                new Document(field + ".addressLine2", new Document("$exists", true)),
                List.of(new Document("$set", setFields)));
    }

    private void migrateAddressArrayField(String collection, String arrayField) {
        Document legacyValue = new Document("$type", "$$entry.addressLine2");
        Document canonicalValue = new Document("$ifNull", List.of("$$entry.address2", "$$entry.addressLine2"));
        Document withCanonicalAddress = new Document("$setField", new Document("field", "address2")
                .append("input", "$$entry")
                .append("value", canonicalValue));
        Document updatedEntry = new Document("$unsetField", new Document("field", "addressLine2")
                .append("input", withCanonicalAddress));
        Document entry = new Document("$cond", List.of(
                new Document("$ne", List.of(legacyValue, "missing")),
                updatedEntry,
                "$$entry"));
        Document addresses = new Document("$map", new Document("input", new Document("$ifNull", List.of("$" + arrayField, List.of())))
                .append("as", "entry")
                .append("in", entry));
        mongoTemplate.getCollection(collection).updateMany(
                new Document(arrayField + ".addressLine2", new Document("$exists", true)),
                List.of(new Document("$set", new Document(arrayField, addresses))));
    }
}
