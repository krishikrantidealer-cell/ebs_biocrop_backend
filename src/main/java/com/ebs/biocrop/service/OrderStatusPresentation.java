package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.OrderStatus;

import java.util.Locale;

/** Maps persisted lifecycle states to role-specific UI labels. */
public final class OrderStatusPresentation {
    private OrderStatusPresentation() { }

    public static String forCustomer(String status) {
        OrderStatus canonical = canonical(status);
        if (canonical == OrderStatus.ORDERED || canonical == OrderStatus.CONFIRMED) return "ORDERED";
        if (canonical == OrderStatus.RTO_SHIPPED) return "Return to origin — shipped";
        if (canonical == OrderStatus.RTO_DELIVERED) return "Return to origin — delivered";
        return canonical == null ? status : canonical.name();
    }

    public static String forSeller(String status) {
        OrderStatus canonical = canonical(status);
        if (canonical == OrderStatus.ORDERED) return "PENDING";
        return canonical == null ? status : canonical.name();
    }

    private static OrderStatus canonical(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        // Compatibility mappings for existing records; the database migration is separate.
        if ("PENDING".equals(normalized)) return OrderStatus.ORDERED;
        if ("CANCELLED".equals(normalized)) return OrderStatus.CANCELED;
        if ("RTO SHIPED".equals(normalized) || "RTO_SHIPED".equals(normalized)) return OrderStatus.RTO_SHIPPED;
        if ("RTO DELIVERED".equals(normalized)) return OrderStatus.RTO_DELIVERED;
        try {
            return OrderStatus.valueOf(normalized.replace(' ', '_'));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
