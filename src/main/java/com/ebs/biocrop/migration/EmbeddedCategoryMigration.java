package com.ebs.biocrop.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.List;

/** One-time opt-in converter for the previous embedded category shape. */
@Component
@Order(0)
public class EmbeddedCategoryMigration implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedCategoryMigration.class);
    private final MongoTemplate mongoTemplate;

    @Value("${app.database.category-migration.enabled:false}")
    private boolean enabled;

    public EmbeddedCategoryMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("Embedded category migration is disabled.");
            return;
        }
        if (!mongoTemplate.collectionExists("categories")) {
            throw new IllegalStateException("Category migration will not create the categories collection; create/approve it first.");
        }
        MongoCollection<Document> collection = mongoTemplate.getCollection("categories");
        validateThreeLevelData(collection);
        Map<String, Document> existingById = new HashMap<>();
        collection.find().forEach(category -> existingById.put(String.valueOf(category.get("_id")), category));
        List<Document> planned = new ArrayList<>();
        int roots = 0;
        for (Document root : collection.find(Filters.exists("subCategories", true))) {
            Object rootId = root.get("_id");
            String rootIdValue = String.valueOf(rootId);
            String rootName = root.getString("name");
            if (rootName == null || rootName.isBlank()) {
                throw new IllegalStateException("Cannot migrate category with missing name: " + rootIdValue);
            }
            flatten(root, null, 0, root.getInteger("sortOrder", roots + 1), root.get("createdAt"), planned);
            roots++;
        }
        Map<String, Document> uniquePlanned = new HashMap<>();
        for (Document category : planned) {
            String id = String.valueOf(category.get("_id"));
            Document priorPlan = uniquePlanned.putIfAbsent(id, category);
            if (priorPlan != null && !java.util.Objects.equals(priorPlan.get("parentId"), category.get("parentId"))) {
                throw new IllegalStateException("Category ID is embedded under multiple parents: " + id);
            }
            Document existing = existingById.get(id);
            if (existing != null && !java.util.Objects.equals(existing.get("parentId"), category.get("parentId"))) {
                throw new IllegalStateException("Category ID collision while migrating category " + id);
            }
        }
        for (Document category : uniquePlanned.values()) {
            collection.replaceOne(Filters.eq("_id", category.get("_id")), category, new ReplaceOptions().upsert(true));
        }
        log.info("Embedded category migration complete: {} embedded roots normalized; {} category documents flattened.", roots, uniquePlanned.size());
    }

    private void flatten(Document source, String parentId, int level, int sortOrder,
                         Object inheritedCreatedAt, List<Document> output) {
        if (level > 2) throw new IllegalStateException("Category hierarchy cannot exceed level 2.");
        Object id = source.get("_id");
        String name = source.getString("name");
        if (id == null || name == null || name.isBlank()) {
            throw new IllegalStateException("Every category requires an _id and a nonblank name.");
        }
        Date now = Date.from(Instant.now());
        Document flat = new Document(source);
        Object childrenValue = flat.remove("subCategories");
        flat.put("name", name.trim());
        flat.put("slug", slug(source.getString("slug"), name));
        flat.put("parentId", parentId);
        flat.put("level", level);
        flat.put("sortOrder", source.getInteger("sortOrder", sortOrder));
        flat.put("isActive", source.get("isActive", Boolean.class) == null ? true : source.getBoolean("isActive"));
        flat.putIfAbsent("createdAt", inheritedCreatedAt == null ? now : inheritedCreatedAt);
        flat.put("updatedAt", now);
        output.add(flat);
        if (childrenValue == null) return;
        if (!(childrenValue instanceof Iterable<?> children)) {
            throw new IllegalStateException("Malformed subCategories field on category " + id);
        }
        int order = 0;
        for (Object child : children) {
            if (!(child instanceof Document document)) throw new IllegalStateException("Malformed child category under " + id);
            flatten(document, String.valueOf(id), level + 1, ++order, flat.get("createdAt"), output);
        }
    }

    private void validateThreeLevelData(MongoCollection<Document> collection) {
        var allCategories = collection.find().into(new ArrayList<Document>());
        Map<String, Document> categoriesById = new HashMap<>();
        Map<String, String> embeddedChildOwners = new HashMap<>();
        for (Document category : allCategories) {
            categoriesById.put(String.valueOf(category.get("_id")), category);
        }
        for (Document category : allCategories) {
            Object rawLevel = category.get("level");
            if (rawLevel instanceof Number level && level.intValue() > 2) {
                throw new IllegalStateException("Category data contains a level deeper than level 2: "
                        + category.get("_id"));
            }
            Set<String> ancestors = new HashSet<>();
            Document cursor = category;
            int depth = 0;
            while (cursor.get("parentId") != null) {
                if (++depth > 2) {
                    throw new IllegalStateException("Category data contains a hierarchy deeper than level 2: "
                            + category.get("_id"));
                }
                String parentId = String.valueOf(cursor.get("parentId"));
                if (!ancestors.add(parentId)) {
                    throw new IllegalStateException("Category parent cycle detected at " + category.get("_id"));
                }
                cursor = categoriesById.get(parentId);
                if (cursor == null) break;
            }
            Object rawChildren = category.get("subCategories");
            if (rawChildren == null) continue;
            if (!(rawChildren instanceof Iterable<?> children)) {
                throw new IllegalStateException("Malformed subCategories field on category " + category.get("_id"));
            }
            for (Object childValue : children) {
                if (!(childValue instanceof Document child)) {
                    throw new IllegalStateException("Malformed subcategory under " + category.get("_id"));
                }
                Object childId = child.get("_id");
                if (childId == null) {
                    throw new IllegalStateException("A subcategory has no ID under category " + category.get("_id"));
                }
                String childIdValue = String.valueOf(childId);
                if (category.get("parentId") != null && category.get("level") instanceof Number parentLevel
                        && parentLevel.intValue() >= 2) {
                    throw new IllegalStateException("A level-2 category cannot have subcategories: " + category.get("_id"));
                }
                Object childLevel = child.get("level");
                if (childLevel instanceof Number level && level.intValue() > 2) {
                    throw new IllegalStateException("Embedded category has an unsupported level: " + childIdValue);
                }
                String owner = String.valueOf(category.get("_id"));
                String previousOwner = embeddedChildOwners.putIfAbsent(childIdValue, owner);
                if (previousOwner != null && !previousOwner.equals(owner)) {
                    throw new IllegalStateException("Embedded subcategory ID is used under multiple parents: " + childIdValue);
                }
                Document existing = categoriesById.get(childIdValue);
                if (existing != null && !owner.equals(existing.getString("parentId"))) {
                    throw new IllegalStateException("Embedded subcategory ID collides with an unrelated category: " + childIdValue);
                }
                Object nested = child.get("subCategories");
                if (nested instanceof Iterable<?> nestedChildren && nestedChildren.iterator().hasNext()) {
                    for (Object nestedValue : nestedChildren) {
                        if (!(nestedValue instanceof Document nestedCategory) || nestedCategory.get("_id") == null
                                || nestedCategory.getString("name") == null || nestedCategory.getString("name").isBlank()) {
                            throw new IllegalStateException("Malformed level-2 category under " + childIdValue);
                        }
                        String nestedId = String.valueOf(nestedCategory.get("_id"));
                        String previousNestedOwner = embeddedChildOwners.putIfAbsent(nestedId, childIdValue);
                        if (previousNestedOwner != null && !previousNestedOwner.equals(childIdValue)) {
                            throw new IllegalStateException("Embedded category ID is used under multiple parents: " + nestedId);
                        }
                        Document existingNested = categoriesById.get(nestedId);
                        if (existingNested != null && !childIdValue.equals(existingNested.getString("parentId"))) {
                            throw new IllegalStateException("Embedded category ID collides with an unrelated category: " + nestedId);
                        }
                    }
                }
                if (nested != null && !(nested instanceof Iterable<?>)) {
                    throw new IllegalStateException("Malformed nested subCategories field under category " + category.get("_id"));
                }
            }
        }
    }

    private String slug(String existing, String name) {
        String source = existing == null || existing.isBlank() ? name : existing;
        return source.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }
}
