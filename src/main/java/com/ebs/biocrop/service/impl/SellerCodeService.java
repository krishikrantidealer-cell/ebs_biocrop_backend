package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;

/** Assigns stable seller prefixes from the existing users collection. */
@Service
public class SellerCodeService {
    private final MongoTemplate mongoTemplate;

    public SellerCodeService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public String ensureSellerCode(String sellerId) {
        if (sellerId == null || sellerId.isBlank()) {
            throw new AppException("Seller ID is required to generate SKUs", HttpStatus.CONFLICT);
        }
        User seller = mongoTemplate.findById(sellerId, User.class);
        if (seller == null || seller.getRole() != UserRole.ROLE_SELLER) {
            throw new ResourceNotFoundException("Seller", "id", sellerId);
        }
        if (seller.getSellerCode() != null && !seller.getSellerCode().isBlank()) return seller.getSellerCode();

        for (int attempt = 0; attempt < 20; attempt++) {
            long candidate = nextSellerNumber();
            String code = formatSellerCode(candidate);
            Query claim = Query.query(new Criteria().andOperator(
                    Criteria.where("_id").is(sellerId),
                    Criteria.where("role").is(UserRole.ROLE_SELLER),
                    new Criteria().orOperator(Criteria.where("sellerCode").exists(false), Criteria.where("sellerCode").is(null))));
            try {
                User claimed = mongoTemplate.findAndModify(claim,
                        new Update().set("sellerCode", code).inc("version", 1),
                        org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
                        User.class, "users");
                if (claimed != null) return claimed.getSellerCode();
                seller = mongoTemplate.findById(sellerId, User.class);
                if (seller == null || seller.getRole() != UserRole.ROLE_SELLER) {
                    throw new ResourceNotFoundException("Seller", "id", sellerId);
                }
                if (seller.getSellerCode() != null && !seller.getSellerCode().isBlank()) return seller.getSellerCode();
            } catch (DuplicateKeyException collision) {
                // Another concurrent seller claim won this number; retry from the current maximum.
            }
        }
        throw new AppException("Could not allocate a unique seller code", HttpStatus.CONFLICT);
    }

    private long nextSellerNumber() {
        long highest = 0;
        Query codedSellers = Query.query(Criteria.where("role").is(UserRole.ROLE_SELLER)
                .and("sellerCode").regex("^S[0-9]+$"));
        codedSellers.fields().include("sellerCode");
        for (User seller : mongoTemplate.find(codedSellers, User.class, "users")) {
            highest = Math.max(highest, parseSellerNumber(seller.getSellerCode()));
        }
        // Preserve prefixes still referenced by catalog and order history, including sellers
        // whose user record has since been removed. No SKU values are rewritten here.
        highest = Math.max(highest, maxReferencedSellerNumber("products", "sku", false));
        highest = Math.max(highest, maxReferencedSellerNumber("orders", "items.sku", true));
        return highest + 1;
    }

    private long maxReferencedSellerNumber(String collection, String field, boolean orderItems) {
        if (!mongoTemplate.collectionExists(collection)) return 0;
        List<Document> pipeline = new ArrayList<>();
        if (orderItems) pipeline.add(new Document("$unwind", "$items"));
        pipeline.add(new Document("$match", new Document(field, new Document("$regex", "^S[0-9]+-P"))));
        pipeline.add(new Document("$project", new Document("match", new Document("$regexFind", new Document("input", "$" + field)
                .append("regex", "^S([0-9]+)-P")))));
        pipeline.add(new Document("$project", new Document("sellerNumber", new Document("$toLong", new Document("$arrayElemAt", List.of("$match.captures", 0))))));
        pipeline.add(new Document("$group", new Document("_id", null).append("highest", new Document("$max", "$sellerNumber"))));
        List<Document> result = mongoTemplate.getCollection(collection).aggregate(pipeline).into(new ArrayList<>());
        Object highest = result.isEmpty() ? null : result.get(0).get("highest");
        return highest instanceof Number number ? number.longValue() : 0;
    }

    private long parseSellerNumber(String code) {
        if (code == null || !code.matches("^S[0-9]+$")) return 0;
        try { return Long.parseLong(code.substring(1)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    public String productSku(String sellerId, String productCode) {
        if (productCode == null) throw new AppException("Product code is required to generate product SKU", HttpStatus.CONFLICT);
        int separator = productCode.lastIndexOf('-');
        if (separator < 0 || separator == productCode.length() - 1) {
            throw new AppException("Product code does not contain a product sequence", HttpStatus.CONFLICT);
        }
        String sequence = productCode.substring(separator + 1);
        if (!sequence.matches("\\d{1,12}")) throw new AppException("Product code has an invalid product sequence", HttpStatus.CONFLICT);
        return ensureSellerCode(sellerId) + "-P" + String.format(Locale.ROOT, "%06d", Long.parseLong(sequence));
    }

    public static String variantSku(String productSku, int variantNumber) {
        if (variantNumber < 1) throw new IllegalArgumentException("Variant number must be positive");
        return productSku + "-V" + String.format(Locale.ROOT, "%02d", variantNumber);
    }

    private String formatSellerCode(long sellerNumber) {
        return "S" + String.format(Locale.ROOT, "%04d", sellerNumber);
    }
}
