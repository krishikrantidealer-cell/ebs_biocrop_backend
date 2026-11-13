package com.ebs.biocrop.entity.enums;

/**
 * Canonical lifecycle states for orders.
 */
public enum OrderStatus {
    PENDING_PAYMENT,      // Advance / full online payment awaited
    CONFIRMED,            // Payment verified or COD confirmed, waiting for seller
    PROCESSING,           // Seller accepted, items being packed
    DISPATCHED,           // Shipped with carrier
    DELIVERED,            // Handed over to customer
    CANCELLED,            // Order cancelled (uniform British spelling)
    RETURNED              // Returned to seller / RTO
}
