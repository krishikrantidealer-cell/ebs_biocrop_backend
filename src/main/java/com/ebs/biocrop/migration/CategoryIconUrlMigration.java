package com.ebs.biocrop.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Normalizes legacy emoji/non-URL category iconUrl values in MongoDB `categories`
 * into proper HTTPS Twemoji SVG icon URLs.
 */
@Component
public class CategoryIconUrlMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CategoryIconUrlMigration.class);
    private static final String TWEMOJI_SVG_PREFIX = "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/svg/";
    private static final String DEFAULT_ICON_URL = TWEMOJI_SVG_PREFIX + "1f331.svg";

    private final MongoTemplate mongoTemplate;

    public CategoryIconUrlMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!mongoTemplate.collectionExists("categories")) {
            return;
        }
        MongoCollection<Document> collection = mongoTemplate.getCollection("categories");
        int updated = 0;
        for (Document doc : collection.find()) {
            String raw = doc.getString("iconUrl");
            if (isHttpUrl(raw)) {
                continue;
            }
            String resolvedUrl = toTwemojiSvgUrl(raw);
            collection.updateOne(
                    Filters.eq("_id", doc.get("_id")),
                    Updates.combine(
                            Updates.set("iconUrl", resolvedUrl),
                            Updates.set("updatedAt", Instant.now())));
            updated++;
        }
        if (updated > 0) {
            log.info("[MIGRATION] Normalized iconUrl to HTTPS SVG URLs for {} categories.", updated);
        }
    }

    static boolean isHttpUrl(String value) {
        if (value == null) return false;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("https://") || trimmed.startsWith("http://");
    }

    static String toTwemojiSvgUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ICON_URL;
        }
        String trimmed = raw.trim();
        List<String> hexParts = new ArrayList<>();
        int i = 0;
        while (i < trimmed.length()) {
            int cp = trimmed.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0F || cp == 0xFE0E) {
                continue;
            }
            if (cp > 0x7F) {
                hexParts.add(Integer.toHexString(cp).toLowerCase(Locale.ROOT));
            }
        }
        if (hexParts.isEmpty()) {
            return DEFAULT_ICON_URL;
        }
        return TWEMOJI_SVG_PREFIX + String.join("-", hexParts) + ".svg";
    }
}
