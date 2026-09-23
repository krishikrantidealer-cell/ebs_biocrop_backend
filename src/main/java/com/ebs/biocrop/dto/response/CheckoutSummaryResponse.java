package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Address;

import java.util.ArrayList;
import java.util.List;

public class CheckoutSummaryResponse {

    private CartResponse cart;
    private Boolean readyForCheckout;
    private List<String> validationErrors = new ArrayList<>();
    private Address deliveryAddress;
    private Double subtotal;
    private Double totalDiscount;
    private Double shippingCharge;
    private Double finalPayableAmount;

    public CheckoutSummaryResponse() {
    }

    public CheckoutSummaryResponse(CartResponse cart, Boolean readyForCheckout,
                                   List<String> validationErrors, Address deliveryAddress,
                                   Double subtotal, Double totalDiscount,
                                   Double shippingCharge, Double finalPayableAmount) {
        this.cart = cart;
        this.readyForCheckout = readyForCheckout;
        this.validationErrors = validationErrors != null ? validationErrors : new ArrayList<>();
        this.deliveryAddress = deliveryAddress;
        this.subtotal = subtotal;
        this.totalDiscount = totalDiscount;
        this.shippingCharge = shippingCharge;
        this.finalPayableAmount = finalPayableAmount;
    }

    public CartResponse getCart() {
        return cart;
    }

    public void setCart(CartResponse cart) {
        this.cart = cart;
    }

    public Boolean getReadyForCheckout() {
        return readyForCheckout;
    }

    public void setReadyForCheckout(Boolean readyForCheckout) {
        this.readyForCheckout = readyForCheckout;
    }

    public List<String> getValidationErrors() {
        return validationErrors;
    }

    public void setValidationErrors(List<String> validationErrors) {
        this.validationErrors = validationErrors;
    }

    public Address getDeliveryAddress() {
        return deliveryAddress;
    }

    public void setDeliveryAddress(Address deliveryAddress) {
        this.deliveryAddress = deliveryAddress;
    }

    public Double getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(Double subtotal) {
        this.subtotal = subtotal;
    }

    public Double getTotalDiscount() {
        return totalDiscount;
    }

    public void setTotalDiscount(Double totalDiscount) {
        this.totalDiscount = totalDiscount;
    }

    public Double getShippingCharge() {
        return shippingCharge;
    }

    public void setShippingCharge(Double shippingCharge) {
        this.shippingCharge = shippingCharge;
    }

    public Double getFinalPayableAmount() {
        return finalPayableAmount;
    }

    public void setFinalPayableAmount(Double finalPayableAmount) {
        this.finalPayableAmount = finalPayableAmount;
    }
}
