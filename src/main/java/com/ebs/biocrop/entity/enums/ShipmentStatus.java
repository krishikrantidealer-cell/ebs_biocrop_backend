package com.ebs.biocrop.entity.enums;

/**
 * Delhivery B2C-aligned shipment lifecycle statuses.
 * Reference: https://one.delhivery.com/developer-portal/documents/b2c/
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
