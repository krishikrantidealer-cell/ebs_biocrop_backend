package com.ebs.biocrop.entity;

import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Amounts and reconciliation references for payments against one seller order. */
public class OrderPaymentDetails {
    private Double amountPaid = 0.0;
    private Double advanceAmount = 0.0;
    private Double remainingAmount = 0.0;
    private Double paidPercentage = 0.0;
    private Double remainingPercentage = 0.0;
    private List<OrderPaymentTransaction> transactions = new ArrayList<>();

    public OrderPaymentDetails() { }
    public Double getAmountPaid() { return amountPaid; }
    public void setAmountPaid(Double amountPaid) { this.amountPaid = amountPaid; }
    public Double getAdvanceAmount() { return advanceAmount; }
    public void setAdvanceAmount(Double advanceAmount) { this.advanceAmount = advanceAmount; }
    public Double getRemainingAmount() { return remainingAmount; }
    public void setRemainingAmount(Double remainingAmount) { this.remainingAmount = remainingAmount; }
    public Double getPaidPercentage() { return paidPercentage; }
    public void setPaidPercentage(Double paidPercentage) { this.paidPercentage = paidPercentage; }
    public Double getRemainingPercentage() { return remainingPercentage; }
    public void setRemainingPercentage(Double remainingPercentage) { this.remainingPercentage = remainingPercentage; }

    /** Records server-confirmed payment totals and derives the remaining balance and UI percentages. */
    public void recordAmounts(double orderTotal, double confirmedAmountPaid) {
        if (!Double.isFinite(orderTotal) || orderTotal < 0 || !Double.isFinite(confirmedAmountPaid)
                || confirmedAmountPaid < 0 || confirmedAmountPaid > orderTotal) {
            throw new IllegalArgumentException("Confirmed payment amounts must be between zero and the order total");
        }
        BigDecimal total = BigDecimal.valueOf(orderTotal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal paid = BigDecimal.valueOf(confirmedAmountPaid).setScale(2, RoundingMode.HALF_UP);
        BigDecimal remaining = total.subtract(paid).max(BigDecimal.ZERO);
        double previousPaid = this.amountPaid == null ? 0.0 : this.amountPaid;
        this.amountPaid = paid.doubleValue();
        if (previousPaid == 0.0 && paid.signum() > 0) this.advanceAmount = paid.doubleValue();
        this.remainingAmount = remaining.doubleValue();
        this.paidPercentage = percentage(paid, total);
        this.remainingPercentage = percentage(remaining, total);
    }

    private double percentage(BigDecimal amount, BigDecimal total) {
        if (total.signum() == 0) return 0.0;
        return amount.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP).doubleValue();
    }
    public List<OrderPaymentTransaction> getTransactions() { return transactions; }
    public void setTransactions(List<OrderPaymentTransaction> transactions) {
        this.transactions = transactions != null ? transactions : new ArrayList<>();
    }
}
