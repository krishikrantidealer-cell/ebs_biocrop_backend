package com.ebs.biocrop.entity.enums;

/**
 * Shipment lifecycle statuses synced via Velocity Shipping webhooks.
 */
public enum ShipmentStatus {
    PENDING,
    MANIFESTED,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    UNDELIVERED,
    RTO,
    CANCELLED
}
