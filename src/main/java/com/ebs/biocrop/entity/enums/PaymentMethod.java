package com.ebs.biocrop.entity.enums;

/**
 * Payment options and modes used across EBS BioCrop:
 *
 * <h3>1. Customer Checkout Payment Options:</h3>
 * <ul>
 *   <li>{@link #PREPAID} - 100% advance online payment via Razorpay.</li>
 *   <li>{@link #PARTIAL} - Minimum 10% advance online payment, remaining balance collected as COD.</li>
 *   <li>{@link #COD} - 100% Cash on Delivery upon order handover.</li>
 * </ul>
 *
 * <h3>2. Product Catalog Payment Modes (Seller configuration per variant):</h3>
 * <ul>
 *   <li>{@link #ONLINE} - Variant accepts online payment modes only.</li>
 *   <li>{@link #COD} - Variant accepts Cash on Delivery only.</li>
 *   <li>{@link #BOTH} - Variant accepts both Online and COD payments.</li>
 * </ul>
 */
public enum PaymentMethod {
    // Customer Checkout Options
    PREPAID("Prepaid"),
    PARTIAL("Partial"),
    COD("Cod"),

    // Product Variant Catalog Modes
    ONLINE("Online"),
    BOTH("Both");

    private final String displayValue;

    PaymentMethod(String displayValue) {
        this.displayValue = displayValue;
    }

    public String getDisplayValue() {
        return displayValue;
    }

    /** Returns true if this is a customer checkout payment option. */
    public boolean isCheckoutOption() {
        return this == PREPAID || this == PARTIAL || this == COD;
    }

    /** Returns true if this is a product variant catalog acceptance mode. */
    public boolean isCatalogMode() {
        return this == ONLINE || this == COD || this == BOTH;
    }

    /** Returns true if this payment option involves online payment collection. */
    public boolean hasOnlinePayment() {
        return this == PREPAID || this == PARTIAL || this == ONLINE;
    }

    /** Returns true if this payment option involves Cash on Delivery collection. */
    public boolean hasCodPayment() {
        return this == COD || this == PARTIAL;
    }

    public static PaymentMethod fromString(String method) {
        if (method == null || method.isBlank()) {
            return ONLINE;
        }
        for (PaymentMethod pm : values()) {
            if (pm.name().equalsIgnoreCase(method.trim()) || pm.displayValue.equalsIgnoreCase(method.trim())) {
                return pm;
            }
        }
        return ONLINE;
    }
}
