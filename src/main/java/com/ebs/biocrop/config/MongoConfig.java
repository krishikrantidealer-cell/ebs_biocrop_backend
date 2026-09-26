package com.ebs.biocrop.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

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
                    .rename("is_delete", "isDeleted");
            
            mongoTemplate.updateMulti(new Query(), userSchemaUpdate, "users");
            log.info("Successfully migrated 'users' schema (renamed legacy fields).");

        } catch (Exception e) {
            throw new IllegalStateException("Legacy MongoDB migration failed; startup is stopped to avoid using a partially migrated schema.", e);
        }
    }
}
