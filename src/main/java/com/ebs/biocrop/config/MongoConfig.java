package com.ebs.biocrop.config;

import jakarta.annotation.PostConstruct;
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

    public MongoConfig(MappingMongoConverter mappingMongoConverter, MongoTemplate mongoTemplate) {
        this.mappingMongoConverter = mappingMongoConverter;
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void disableAndRemoveClassField() {
        // 1. Disable writing '_class' attribute on all future MongoDB documents
        mappingMongoConverter.setTypeMapper(new DefaultMongoTypeMapper(null));
        log.info("MongoDB MappingMongoConverter configured: '_class' field mapping disabled.");

        // 2. Clean up '_class' field from any existing documents in database
        try {
            mongoTemplate.updateMulti(new Query(), new Update().unset("_class"), "users");
            mongoTemplate.updateMulti(new Query(), new Update().unset("_class"), "products");
            log.info("Successfully removed '_class' attribute from existing documents in 'users' and 'products' collections.");
        } catch (Exception e) {
            log.warn("Could not clean existing '_class' attributes from database: {}", e.getMessage());
        }
    }
}
