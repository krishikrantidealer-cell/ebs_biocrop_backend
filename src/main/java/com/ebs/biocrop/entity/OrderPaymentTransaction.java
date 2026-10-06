package com.ebs.biocrop.entity;

import java.time.LocalDateTime;

/** One payment attempt/reference attached to an order. */
public class OrderPaymentTransaction {
    private Double amount;
    /** Kept open until payment status and transaction lifecycle values are finalized. */
    private String status;
    private String provider;
    private String providerPaymentId;
    private String referenceBankName;
    private String referenceNumber;
    private String paymentProofUrl;
    private LocalDateTime paidAt;

    public OrderPaymentTransaction() { }
    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public void setProviderPaymentId(String providerPaymentId) { this.providerPaymentId = providerPaymentId; }
    public String getReferenceBankName() { return referenceBankName; }
    public void setReferenceBankName(String referenceBankName) { this.referenceBankName = referenceBankName; }
    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }
    public String getPaymentProofUrl() { return paymentProofUrl; }
    public void setPaymentProofUrl(String paymentProofUrl) { this.paymentProofUrl = paymentProofUrl; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
}
