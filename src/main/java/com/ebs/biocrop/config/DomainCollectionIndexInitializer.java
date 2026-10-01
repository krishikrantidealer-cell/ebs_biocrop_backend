package com.ebs.biocrop.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Ensures indexes only on collections that already exist; startup never creates a collection. */
@Component
public class DomainCollectionIndexInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DomainCollectionIndexInitializer.class);
    private final MongoTemplate mongoTemplate;
    @Value("${app.database.maintenance-mode.enabled:false}")
    private boolean maintenanceMode;

    public DomainCollectionIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (maintenanceMode) {
            log.info("Skipping domain collection index initialization in database maintenance mode.");
            return;
        }
        ensure("users", "uniq_user_phone", Map.of("phoneNumber", 1), new Index().on("phoneNumber", Sort.Direction.ASC)
                .unique().named("uniq_user_phone"));
        ensure("users", "uniq_user_email", Map.of("email", 1), new Index().on("email", Sort.Direction.ASC)
                .unique().partial(PartialIndexFilter.of(Criteria.where("email").type(2))).named("uniq_user_email"));
        ensure("users", "user_password_reset_token_hash_idx", Map.of("passwordResetTokenHash", 1),
                new Index().on("passwordResetTokenHash", Sort.Direction.ASC).named("user_password_reset_token_hash_idx"));
        ensure("carts", "uniq_cart_user", Map.of("user", 1), new Index().on("user", Sort.Direction.ASC)
                .unique().named("uniq_cart_user"));
        ensure("wishlists", "uniq_wishlist_user", Map.of("user", 1), new Index().on("user", Sort.Direction.ASC)
                .unique().named("uniq_wishlist_user"));

        ensure("categories", "category_parent_sort_idx", Map.of("parentId", 1, "sortOrder", 1), new Index().on("parentId", Sort.Direction.ASC)
                .on("sortOrder", Sort.Direction.ASC).named("category_parent_sort_idx"));
        ensure("categories", "category_parent_slug_unique_idx", Map.of("parentId", 1, "slug", 1), new Index().on("parentId", Sort.Direction.ASC)
                .on("slug", Sort.Direction.ASC).unique().named("category_parent_slug_unique_idx"));
        ensure("categories", "category_level_idx", Map.of("level", 1), new Index().on("level", Sort.Direction.ASC).named("category_level_idx"));

        ensure("products", "uniq_product_sku", Map.of("sku", 1), new Index().on("sku", Sort.Direction.ASC)
                .unique().named("uniq_product_sku"));
        ensure("products", "product_category_status_idx", Map.of("categoryId", 1, "status", 1), new Index().on("categoryId", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC).named("product_category_status_idx"));
        ensure("products", "product_seller_updated_idx", Map.of("sellerId", 1, "updatedAt", -1), new Index().on("sellerId", Sort.Direction.ASC)
                .on("updatedAt", Sort.Direction.DESC).named("product_seller_updated_idx"));
        ensure("products", "idx_products_variant_id", Map.of("variants._id", 1), new Index().on("variants._id", Sort.Direction.ASC)
                .named("idx_products_variant_id"));
    }

    private void ensure(String collection, String requestedName, Map<String, Integer> requestedKeys, Index index) {
        try {
            if (!mongoTemplate.collectionExists(collection)) {
                log.warn("Skipping an index because existing collection {} is missing; startup will not create it.", collection);
                return;
            }
            List<IndexInfo> existingIndexes = mongoTemplate.indexOps(collection).getIndexInfo();
            boolean nameConflict = existingIndexes.stream()
                    .filter(existing -> requestedName != null && requestedName.equals(existing.getName()))
                    .anyMatch(existing -> !indexKeys(existing).equals(requestedKeys));
            if (nameConflict) {
                log.warn("Skipping index {} on {} because an existing index with that name has a different key definition. " +
                        "Resolve the index drift explicitly; startup will not drop or replace database indexes.", requestedName, collection);
                return;
            }
            mongoTemplate.indexOps(collection).ensureIndex(index);
        } catch (Exception e) {
            log.warn("Could not ensure index {} on {}: {}", requestedName, collection, e.getMessage());
        }
    }

    private Map<String, Integer> indexKeys(IndexInfo indexInfo) {
        Map<String, Integer> keys = new java.util.LinkedHashMap<>();
        indexInfo.getIndexFields().forEach(field -> {
            int direction = field.getDirection() == org.springframework.data.domain.Sort.Direction.DESC ? -1 : 1;
            keys.put(field.getKey(), direction);
        });
        return keys;
    }
}
