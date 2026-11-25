package com.ebs.biocrop.entity.enums;

/**
 * Canonical lifecycle states for orders.
 */
public enum OrderStatus {
    PENDING,              // Newly created order awaiting admin/seller confirmation
    PENDING_PAYMENT,      // Advance / full online payment awaited
    CONFIRMED,            // Confirmed by admin/seller, synced to Velocity Shipping Pending AWB queue
    PROCESSING,           // Legacy alias: seller accepted, items being packed
    PACKED,               // Shipping label downloaded and package packed
    DISPATCHED,           // Legacy alias: shipped with carrier
    SHIPPED,              // Picked up / manifested via Velocity Shipping
    IN_TRANSIT,           // Moving through courier hub network
    OUT_FOR_DELIVERY,     // Out for final-mile delivery
    DELIVERED,            // Handed over to customer
    RTO_INITIATED,        // Return-to-origin initiated by carrier
    RTO_IN_TRANSIT,       // Return shipment moving back to seller warehouse
    RTO_DELIVERED,        // Returned to seller warehouse
    CANCELLED,            // Order cancelled (uniform British spelling)
    RETURNED              // Legacy alias: returned to seller / RTO
}
