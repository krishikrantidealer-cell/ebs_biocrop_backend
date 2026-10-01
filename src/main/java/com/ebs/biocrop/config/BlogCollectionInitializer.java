package com.ebs.biocrop.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BlogCollectionInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BlogCollectionInitializer.class);
    private final MongoTemplate mongoTemplate;
    @Value("${app.database.maintenance-mode.enabled:false}")
    private boolean maintenanceMode;

    public BlogCollectionInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (maintenanceMode) {
            log.info("Skipping blog index initialization in database maintenance mode.");
            return;
        }
        if (!mongoTemplate.collectionExists("blogs")) {
            log.warn("Skipping blog index initialization because the existing blogs collection is missing; startup will not create it.");
            return;
        }
        var indexes = mongoTemplate.indexOps("blogs");
        indexes.ensureIndex(new Index().on("slug", Sort.Direction.ASC).unique().named("uniq_blog_slug"));
        indexes.ensureIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .on("publishedAt", Sort.Direction.DESC)
                .on("_id", Sort.Direction.DESC)
                .named("blogs_public_latest"));
        indexes.ensureIndex(new Index()
                .on("category.slug", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC)
                .on("publishedAt", Sort.Direction.DESC)
                .on("_id", Sort.Direction.DESC)
                .named("blogs_category_latest"));
        indexes.ensureIndex(new Index().on("updatedAt", Sort.Direction.DESC).named("blogs_admin_updated"));
        indexes.ensureIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .on("scheduledAt", Sort.Direction.ASC)
                .on("deletedAt", Sort.Direction.ASC)
                .named("blogs_scheduled_publication"));
        log.info("MongoDB blogs collection indexes are ready.");
    }
}
