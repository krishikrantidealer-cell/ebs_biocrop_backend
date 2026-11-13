package com.ebs.biocrop.migration;

import com.ebs.biocrop.entity.enums.OrderStatus;
import com.ebs.biocrop.entity.enums.PaymentStatus;
import com.ebs.biocrop.entity.enums.ShipmentStatus;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Automatically aligns legacy order and payment status strings in MongoDB
 * to the canonical OrderStatus, PaymentStatus, and ShipmentStatus enums.
 * This runs idempotently on startup.
 */
@Component
@Order(1)
public class OrderStatusDataMigration implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(OrderStatusDataMigration.class);
    private final MongoTemplate mongoTemplate;

    @Value("${app.database.order-status-migration.enabled:true}")
    private boolean enabled;

    public OrderStatusDataMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("Order status data migration is disabled.");
            return;
        }
        if (!mongoTemplate.collectionExists("orders")) {
            return;
        }

        try {
            MongoCollection<Document> collection = mongoTemplate.getCollection("orders");

            long updatedOverall = collection.updateMany(
                    Filters.eq("overallStatus", "AWAITING_ADVANCE_PAYMENT"),
                    Updates.set("overallStatus", OrderStatus.PENDING_PAYMENT.name())
            ).getModifiedCount();

            long updatedConfirmed = collection.updateMany(
                    Filters.eq("overallStatus", "AWAITING_SELLER_RESPONSE"),
                    Updates.set("overallStatus", OrderStatus.CONFIRMED.name())
            ).getModifiedCount();

            long updatedProcessing = collection.updateMany(
                    Filters.in("overallStatus", "ACCEPTED", "PACKED"),
                    Updates.set("overallStatus", OrderStatus.PROCESSING.name())
            ).getModifiedCount();

            long updatedDispatched = collection.updateMany(
                    Filters.eq("overallStatus", "SHIPPED"),
                    Updates.set("overallStatus", OrderStatus.DISPATCHED.name())
            ).getModifiedCount();

            long updatedCancelled = collection.updateMany(
                    Filters.eq("overallStatus", "CANCELED"),
                    Updates.set("overallStatus", OrderStatus.CANCELLED.name())
            ).getModifiedCount();

            long updatedReturned = collection.updateMany(
                    Filters.in("overallStatus", "RTO_DELIVERED", "RTO_SHIPPED"),
                    Updates.set("overallStatus", OrderStatus.RETURNED.name())
            ).getModifiedCount();

            long updatedPaymentCreated = collection.updateMany(
                    Filters.in("payment.status", "AWAITING_ADVANCE_PAYMENT", "AWAITING_COD_COLLECTION"),
                    Updates.set("payment.status", PaymentStatus.CREATED.name())
            ).getModifiedCount();

            long updatedPaymentCaptured = collection.updateMany(
                    Filters.in("payment.status", "PAID", "PARTIALLY_PAID"),
                    Updates.set("payment.status", PaymentStatus.CAPTURED.name())
            ).getModifiedCount();

            // Nested items and sellerFulfillments alignment
            long nestedUpdated = 0;
            for (Document doc : collection.find(Filters.or(
                    Filters.regex("items.itemStatus", "AWAITING|ACCEPTED|PACKED|SHIPPED|CANCELED|RTO"),
                    Filters.regex("sellerFulfillments.status", "AWAITING|ACCEPTED|PACKED|SHIPPED|CANCELED|REJECTED|RTO"),
                    Filters.regex("sellerFulfillments.shipments.status", "NOT_CREATED|CANCELED")
            ))) {
                boolean modified = false;

                List<Document> items = doc.getList("items", Document.class);
                if (items != null) {
                    for (Document item : items) {
                        String st = item.getString("itemStatus");
                        String migrated = migrateOrderStatus(st);
                        if (st != null && !st.equals(migrated)) {
                            item.put("itemStatus", migrated);
                            modified = true;
                        }
                    }
                }

                List<Document> fulfillments = doc.getList("sellerFulfillments", Document.class);
                if (fulfillments != null) {
                    for (Document sf : fulfillments) {
                        String st = sf.getString("status");
                        String migrated = migrateOrderStatus(st);
                        if (st != null && !st.equals(migrated)) {
                            sf.put("status", migrated);
                            modified = true;
                        }

                        List<Document> shipments = sf.getList("shipments", Document.class);
                        if (shipments != null) {
                            for (Document sh : shipments) {
                                String shSt = sh.getString("status");
                                if ("NOT_CREATED".equalsIgnoreCase(shSt)) {
                                    sh.put("status", ShipmentStatus.PENDING.name());
                                    modified = true;
                                } else if ("CANCELED".equalsIgnoreCase(shSt)) {
                                    sh.put("status", ShipmentStatus.CANCELLED.name());
                                    modified = true;
                                }
                            }
                        }
                    }
                }

                if (modified) {
                    collection.replaceOne(Filters.eq("_id", doc.get("_id")), doc);
                    nestedUpdated++;
                }
            }

            long totalUpdated = updatedOverall + updatedConfirmed + updatedProcessing + updatedDispatched
                    + updatedCancelled + updatedReturned + updatedPaymentCreated + updatedPaymentCaptured + nestedUpdated;

            if (totalUpdated > 0) {
                log.info("Order status data migration successfully updated documents (Overall: {}, Nested: {}, Payment: {})",
                        (updatedOverall + updatedConfirmed + updatedProcessing + updatedDispatched + updatedCancelled + updatedReturned),
                        nestedUpdated,
                        (updatedPaymentCreated + updatedPaymentCaptured));
            } else {
                log.info("Order status data migration: all existing documents in 'orders' are already aligned.");
            }
        } catch (Exception e) {
            log.warn("Order status data migration encountered an issue: {}", e.getMessage());
        }
    }

    private String migrateOrderStatus(String status) {
        if (status == null) return OrderStatus.PENDING_PAYMENT.name();
        return switch (status.trim().toUpperCase()) {
            case "AWAITING_ADVANCE_PAYMENT" -> OrderStatus.PENDING_PAYMENT.name();
            case "AWAITING_SELLER_RESPONSE" -> OrderStatus.CONFIRMED.name();
            case "ACCEPTED", "PACKED" -> OrderStatus.PROCESSING.name();
            case "SHIPPED" -> OrderStatus.DISPATCHED.name();
            case "CANCELED", "REJECTED" -> OrderStatus.CANCELLED.name();
            case "RTO_DELIVERED", "RTO_SHIPPED" -> OrderStatus.RETURNED.name();
            default -> status;
        };
    }
}
