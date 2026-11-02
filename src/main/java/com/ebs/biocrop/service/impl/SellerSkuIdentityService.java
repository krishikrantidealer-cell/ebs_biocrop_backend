package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.exception.AppException;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Locale;

/** Assigns stable, readable seller SKU prefixes without adding fields to seller documents. */
@Service
public class SellerSkuIdentityService {
    public static final String COLLECTION = "seller_sku_identities";
    private static final String COUNTER_ID = "__seller_number_counter__";
    private final MongoTemplate mongoTemplate;

    public SellerSkuIdentityService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public String ensureSellerCode(String sellerId) {
        if (sellerId == null || sellerId.isBlank()) {
            throw new AppException("Seller ID is required to generate SKUs", HttpStatus.CONFLICT);
        }
        Document existing = mongoTemplate.findById(sellerId, Document.class, COLLECTION);
        if (existing != null && existing.get("sellerNumber") instanceof Number number) {
            return formatSellerCode(number.longValue());
        }

        Document counter = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(COUNTER_ID)),
                new Update().inc("nextSellerNumber", 1),
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                Document.class,
                COLLECTION);
        if (counter == null || !(counter.get("nextSellerNumber") instanceof Number number)) {
            throw new AppException("Could not allocate a seller SKU number", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        long assigned = number.longValue();
        try {
            mongoTemplate.insert(new Document("_id", sellerId).append("sellerNumber", assigned));
            return formatSellerCode(assigned);
        } catch (DuplicateKeyException concurrentAssignment) {
            Document winner = mongoTemplate.findById(sellerId, Document.class, COLLECTION);
            if (winner != null && winner.get("sellerNumber") instanceof Number winnerNumber) {
                return formatSellerCode(winnerNumber.longValue());
            }
            throw concurrentAssignment;
        }
    }

    public String productSku(String sellerId, String productCode) {
        if (productCode == null) {
            throw new AppException("Product code is required to generate product SKU", HttpStatus.CONFLICT);
        }
        int separator = productCode.lastIndexOf('-');
        if (separator < 0 || separator == productCode.length() - 1) {
            throw new AppException("Product code does not contain a product sequence", HttpStatus.CONFLICT);
        }
        String sequence = productCode.substring(separator + 1);
        if (!sequence.matches("\\d{1,12}")) {
            throw new AppException("Product code has an invalid product sequence", HttpStatus.CONFLICT);
        }
        return ensureSellerCode(sellerId) + "-P" + String.format(Locale.ROOT, "%06d", Long.parseLong(sequence));
    }

    public static String variantSku(String productSku, int variantNumber) {
        if (variantNumber < 1) throw new IllegalArgumentException("Variant number must be positive");
        return productSku + "-V" + String.format(Locale.ROOT, "%02d", variantNumber);
    }

    public static String formatSellerCode(long sellerNumber) {
        if (sellerNumber < 1) throw new IllegalArgumentException("Seller number must be positive");
        return "S" + String.format(Locale.ROOT, "%04d", sellerNumber);
    }
}
