package com.ebs.biocrop.entity;

import org.springframework.data.mongodb.core.mapping.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Provider-neutral payment state. External gateway fields stay null until an integration is selected. */
public class OrderPaymentDetails {
    @Field(write = Field.Write.ALWAYS) private String paymentType = "PARTIAL_ADVANCE_COD";
    @Field(write = Field.Write.ALWAYS) private String status = "AWAITING_ADVANCE_PAYMENT";
    @Field(write = Field.Write.ALWAYS) private BigDecimal advancePercentage = new BigDecimal("10.00");
    @Field(write = Field.Write.ALWAYS) private BigDecimal amountPaid = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private BigDecimal advanceAmountDue = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private BigDecimal advanceAmountPaid = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private BigDecimal codDue = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private BigDecimal codCollected = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private BigDecimal remainingAmount = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private BigDecimal paidPercentage = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private BigDecimal remainingPercentage = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private String provider;
    @Field(write = Field.Write.ALWAYS) private String providerOrderId;
    @Field(write = Field.Write.ALWAYS) private List<OrderPaymentTransaction> transactions = new ArrayList<>();
    @Field(write = Field.Write.ALWAYS) private List<Order.Refund> refunds = new ArrayList<>();

    public String getPaymentType() { return paymentType; }
    public void setPaymentType(String value) { paymentType = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public BigDecimal getAdvancePercentage() { return advancePercentage; }
    public void setAdvancePercentage(BigDecimal value) { advancePercentage = value == null ? BigDecimal.ZERO : value; }
    public BigDecimal getAmountPaid() { return amountPaid; }
    public void setAmountPaid(BigDecimal value) { amountPaid = zero(value); }
    public BigDecimal getAdvanceAmountDue() { return advanceAmountDue; }
    public void setAdvanceAmountDue(BigDecimal value) { advanceAmountDue = zero(value); }
    public BigDecimal getAdvanceAmountPaid() { return advanceAmountPaid; }
    public void setAdvanceAmountPaid(BigDecimal value) { advanceAmountPaid = zero(value); }
    public BigDecimal getCodDue() { return codDue; }
    public void setCodDue(BigDecimal value) { codDue = zero(value); }
    public BigDecimal getCodCollected() { return codCollected; }
    public void setCodCollected(BigDecimal value) { codCollected = zero(value); }
    public BigDecimal getRemainingAmount() { return remainingAmount; }
    public void setRemainingAmount(BigDecimal value) { remainingAmount = zero(value); }
    public BigDecimal getPaidPercentage() { return paidPercentage; }
    public void setPaidPercentage(BigDecimal value) { paidPercentage = zero(value); }
    public BigDecimal getRemainingPercentage() { return remainingPercentage; }
    public void setRemainingPercentage(BigDecimal value) { remainingPercentage = zero(value); }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getProviderOrderId() { return providerOrderId; }
    public void setProviderOrderId(String value) { providerOrderId = value; }
    public List<OrderPaymentTransaction> getTransactions() { return transactions; }
    public void setTransactions(List<OrderPaymentTransaction> values) { transactions = values == null ? new ArrayList<>() : values; }
    public List<Order.Refund> getRefunds() { return refunds; }
    public void setRefunds(List<Order.Refund> values) { refunds = values == null ? new ArrayList<>() : values; }
    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
