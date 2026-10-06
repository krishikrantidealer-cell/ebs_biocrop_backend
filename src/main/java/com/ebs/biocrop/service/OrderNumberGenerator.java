package com.ebs.biocrop.service;

import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Issues sequential ORD identifiers atomically per seller using the existing users collection. */
@Component
public class OrderNumberGenerator {
    private final MongoTemplate mongoTemplate;

    public OrderNumberGenerator(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public String nextForSeller(String sellerId) {
        if (!ObjectId.isValid(sellerId)) {
            throw new AppException("Seller ID must be a valid MongoDB ObjectId", HttpStatus.CONFLICT);
        }
        Query sellerQuery = Query.query(Criteria.where("_id").is(new ObjectId(sellerId)));
        SellerSequence sequence = mongoTemplate.findAndModify(
                sellerQuery,
                new Update().inc("orderNumberSequence", 1),
                FindAndModifyOptions.options().returnNew(true),
                SellerSequence.class,
                "users");
        if (sequence == null || sequence.getOrderNumberSequence() == null) {
            throw new ResourceNotFoundException("Seller", "id", sellerId);
        }
        return "ORD-" + sellerId.toUpperCase(Locale.ROOT) + "-"
                + String.format(Locale.ROOT, "%06d", sequence.getOrderNumberSequence());
    }

    private static class SellerSequence {
        private Long orderNumberSequence;
        public Long getOrderNumberSequence() { return orderNumberSequence; }
        public void setOrderNumberSequence(Long orderNumberSequence) { this.orderNumberSequence = orderNumberSequence; }
    }
}
