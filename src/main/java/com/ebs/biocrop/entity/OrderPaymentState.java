package com.ebs.biocrop.entity;

/** Provider-neutral collection state, separate from the final paid/partially-paid status. */
public enum OrderPaymentState {
    AWAITING_PAYMENT,
    PAYMENT_CONFIRMATION_PENDING,
    PAYMENT_FAILED,
    PAYMENT_CONFIRMED
}
