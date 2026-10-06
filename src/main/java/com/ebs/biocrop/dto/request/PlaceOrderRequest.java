package com.ebs.biocrop.dto.request;

import com.ebs.biocrop.entity.OrderPaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class PlaceOrderRequest {
    @NotNull
    private OrderPaymentMethod paymentMethod;
    private BigDecimal advancePercentage;
    @NotNull
    @Valid
    private AddressInput shippingAddress;
    @Valid
    private AddressInput billingAddress;

    public OrderPaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(OrderPaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public BigDecimal getAdvancePercentage() { return advancePercentage; }
    public void setAdvancePercentage(BigDecimal advancePercentage) { this.advancePercentage = advancePercentage; }
    public AddressInput getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(AddressInput shippingAddress) { this.shippingAddress = shippingAddress; }
    public AddressInput getBillingAddress() { return billingAddress; }
    public void setBillingAddress(AddressInput billingAddress) { this.billingAddress = billingAddress; }

    public static class AddressInput {
        @jakarta.validation.constraints.NotBlank private String name;
        @jakarta.validation.constraints.NotBlank private String phoneNumber;
        @jakarta.validation.constraints.NotBlank private String villageArea;
        private String addressLine2;
        @jakarta.validation.constraints.NotBlank private String cityTehsil;
        @jakarta.validation.constraints.NotBlank private String state;
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Pattern(regexp = "^[0-9]{6}$") private String pincode;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPhoneNumber() { return phoneNumber; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
        public String getVillageArea() { return villageArea; }
        public void setVillageArea(String villageArea) { this.villageArea = villageArea; }
        public String getAddressLine2() { return addressLine2; }
        public void setAddressLine2(String addressLine2) { this.addressLine2 = addressLine2; }
        public String getCityTehsil() { return cityTehsil; }
        public void setCityTehsil(String cityTehsil) { this.cityTehsil = cityTehsil; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public String getPincode() { return pincode; }
        public void setPincode(String pincode) { this.pincode = pincode; }
    }
}
