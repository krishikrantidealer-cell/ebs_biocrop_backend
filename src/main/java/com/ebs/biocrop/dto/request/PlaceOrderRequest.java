package com.ebs.biocrop.dto.request;

import com.ebs.biocrop.entity.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public class PlaceOrderRequest {
    @NotNull
    private PaymentMethod paymentMethod;
    private BigDecimal advancePercentage;
    private BigDecimal advanceAmount;
    @NotNull
    @Valid
    private AddressInput shippingAddress;
    @Valid
    private AddressInput billingAddress;
    @Valid
    private List<ItemInput> items;
    @Valid
    private PrepaidPaymentInput prepaidPayment;

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public BigDecimal getAdvancePercentage() { return advancePercentage; }
    public void setAdvancePercentage(BigDecimal advancePercentage) { this.advancePercentage = advancePercentage; }
    public BigDecimal getAdvanceAmount() { return advanceAmount; }
    public void setAdvanceAmount(BigDecimal advanceAmount) { this.advanceAmount = advanceAmount; }
    public AddressInput getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(AddressInput shippingAddress) { this.shippingAddress = shippingAddress; }
    public AddressInput getBillingAddress() { return billingAddress; }
    public void setBillingAddress(AddressInput billingAddress) { this.billingAddress = billingAddress; }
    public List<ItemInput> getItems() { return items; }
    public void setItems(List<ItemInput> items) { this.items = items; }
    public PrepaidPaymentInput getPrepaidPayment() { return prepaidPayment; }
    public void setPrepaidPayment(PrepaidPaymentInput prepaidPayment) { this.prepaidPayment = prepaidPayment; }

    public static class PrepaidPaymentInput {
        private String paymentChannel;
        private String transactionReference;
        private Boolean markAsPaid;
        private java.time.LocalDateTime paidAt;
        private String notes;

        public String getPaymentChannel() { return paymentChannel; }
        public void setPaymentChannel(String paymentChannel) { this.paymentChannel = paymentChannel; }
        public String getTransactionReference() { return transactionReference; }
        public void setTransactionReference(String transactionReference) { this.transactionReference = transactionReference; }
        public Boolean getMarkAsPaid() { return markAsPaid; }
        public void setMarkAsPaid(Boolean markAsPaid) { this.markAsPaid = markAsPaid; }
        public java.time.LocalDateTime getPaidAt() { return paidAt; }
        public void setPaidAt(java.time.LocalDateTime paidAt) { this.paidAt = paidAt; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    public static class ItemInput {
        private String productId;
        @NotBlank
        private String variantId;
        @NotNull
        @Min(1)
        private Integer quantity;

        public String getProductId() { return productId; }
        public void setProductId(String productId) { this.productId = productId; }
        public String getVariantId() { return variantId; }
        public void setVariantId(String variantId) { this.variantId = variantId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }

    public static class AddressInput {
        @jakarta.validation.constraints.NotBlank private String name;
        @jakarta.validation.constraints.NotBlank private String phoneNumber;
        @jakarta.validation.constraints.NotBlank private String villageArea;
        private String address2;
        @jakarta.validation.constraints.NotBlank private String cityTehsil;
        private String district;
        @jakarta.validation.constraints.NotBlank private String state;
        private String country = "India";
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Pattern(regexp = "^[0-9]{6}$") private String pincode;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPhoneNumber() { return phoneNumber; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
        public String getVillageArea() { return villageArea; }
        public void setVillageArea(String villageArea) { this.villageArea = villageArea; }
        public String getAddress2() { return address2; }
        public void setAddress2(String address2) { this.address2 = address2; }
        public String getCityTehsil() { return cityTehsil; }
        public void setCityTehsil(String cityTehsil) { this.cityTehsil = cityTehsil; }
        public String getDistrict() { return district; }
        public void setDistrict(String district) { this.district = district; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }
        public String getPincode() { return pincode; }
        public void setPincode(String pincode) { this.pincode = pincode; }
    }
}
