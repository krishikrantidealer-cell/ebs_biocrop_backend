package com.ebs.biocrop.entity;

import org.springframework.data.mongodb.core.mapping.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One append-only payment attempt or collection event. */
public class OrderPaymentTransaction {
    @Field(write = Field.Write.ALWAYS) private String transactionId;
    @Field(write = Field.Write.ALWAYS) private String type;
    @Field(write = Field.Write.ALWAYS) private BigDecimal amount = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private String status = "PENDING";
    @Field(write = Field.Write.ALWAYS) private String provider;
    @Field(write = Field.Write.ALWAYS) private String providerPaymentId;
    @Field(write = Field.Write.ALWAYS) private String referenceBankName;
    @Field(write = Field.Write.ALWAYS) private String referenceNumber;
    @Field(write = Field.Write.ALWAYS) private String paymentProofUrl;
    @Field(write = Field.Write.ALWAYS) private LocalDateTime occurredAt;

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String value) { transactionId = value; }
    public String getType() { return type; }
    public void setType(String value) { type = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value == null ? BigDecimal.ZERO : value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public void setProviderPaymentId(String value) { providerPaymentId = value; }
    public String getReferenceBankName() { return referenceBankName; }
    public void setReferenceBankName(String value) { referenceBankName = value; }
    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String value) { referenceNumber = value; }
    public String getPaymentProofUrl() { return paymentProofUrl; }
    public void setPaymentProofUrl(String value) { paymentProofUrl = value; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime value) { occurredAt = value; }
    @org.springframework.data.annotation.Transient public LocalDateTime getPaidAt() { return occurredAt; }
    public void setPaidAt(LocalDateTime value) { occurredAt = value; }
}
