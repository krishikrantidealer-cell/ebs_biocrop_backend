package com.ebs.biocrop.entity;

/** Canonical lifecycle states persisted in orders.orderStatus. */
public enum OrderStatus {
    ORDERED,
    CONFIRMED,
    PACKED,
    SHIPPED,
    DELIVERED,
    CANCELED,
    RTO_SHIPPED,
    RTO_DELIVERED,
    HOLD
}
