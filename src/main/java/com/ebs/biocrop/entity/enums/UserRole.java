package com.ebs.biocrop.entity.enums;

import java.util.Locale;

public enum UserRole {
    ROLE_CUSTOMER("customer"),
    ROLE_SELLER("seller"),
    ROLE_ADMIN("admin");

    private final String value;

    UserRole(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static UserRole fromString(String role) {
        if (role == null || role.isBlank()) {
            return ROLE_CUSTOMER;
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (!normalized.startsWith("ROLE_")) {
            normalized = "ROLE_" + normalized;
        }
        for (UserRole r : values()) {
            if (r.name().equalsIgnoreCase(normalized)) {
                return r;
            }
        }
        return ROLE_CUSTOMER;
    }
}
