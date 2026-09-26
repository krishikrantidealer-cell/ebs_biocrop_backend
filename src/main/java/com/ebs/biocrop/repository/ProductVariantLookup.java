package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.Product;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class ProductVariantLookup {

    private final MongoTemplate mongoTemplate;

    public ProductVariantLookup(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    void ensureVariantIdIndex() {
        mongoTemplate.indexOps("products").ensureIndex(
                new Index().on("variants._id", Sort.Direction.ASC).named("idx_products_variant_id"));
    }

    public List<Product> findProductsContainingVariants(Collection<String> variantIds) {
        LinkedHashSet<Object> storedIds = new LinkedHashSet<>();
        for (String id : variantIds) {
            if (id == null || !ObjectId.isValid(id.trim())) {
                continue;
            }
            String normalizedId = new ObjectId(id.trim()).toHexString();
            storedIds.add(new ObjectId(normalizedId));
            storedIds.add(normalizedId);
        }
        if (storedIds.isEmpty()) {
            return List.of();
        }

        Document filter = new Document("variants._id", new Document("$in", new ArrayList<>(storedIds)));
        MongoCollection<Document> products = mongoTemplate.getCollection("products");
        List<Product> matches = new ArrayList<>();
        products.find(filter).forEach(document -> matches.add(
                mongoTemplate.getConverter().read(Product.class, document)));
        return matches;
    }
}
