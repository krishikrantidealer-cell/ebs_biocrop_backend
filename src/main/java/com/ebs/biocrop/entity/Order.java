package com.ebs.biocrop.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** One seller's fulfillment unit from a customer's checkout. */
@Document(collection = "orders")
@CompoundIndexes({
        @CompoundIndex(name = "uniq_order_seller_number", def = "{ 'sellerId': 1, 'orderNumber': 1 }", unique = true),
        @CompoundIndex(name = "order_customer_created_idx", def = "{ 'customerId': 1, 'createdAt': -1 }"),
        @CompoundIndex(name = "order_seller_created_idx", def = "{ 'sellerId': 1, 'createdAt': -1 }"),
        @CompoundIndex(name = "order_checkout_group_idx", def = "{ 'checkoutGroupId': 1 }")
})
public class Order {

    @Id
    private String id;

    /** Human-readable seller order number; separate from MongoDB id. */
    private String orderNumber;
    /** Shared by all seller orders created from one checkout. */
    private String checkoutGroupId;

    private String customerId;
    private OrderCustomerSnapshot customerSnapshot;
    private String sellerId;
    private OrderSellerSnapshot sellerSnapshot;

    private List<OrderItem> items = new ArrayList<>();

    private Double subtotalAmount = 0.0;
    private Double discountAmount = 0.0;
    private Double shippingAmount = 0.0;
    private Double totalAmount = 0.0;
    private String currency = "INR";

    private OrderPaymentMethod paymentMethod;
    private OrderPaymentState paymentState = OrderPaymentState.AWAITING_PAYMENT;
    /** Requested upfront share of totalAmount; for PREPAID this is 100. */
    private Double advancePercentage;
    /** Amount due for the initial advance; not evidence of a received payment. */
    private Double advanceAmountDue;
    /** PAID or PARTIALLY_PAID after payment confirmation; null until the first payment is verified. */
    private String paymentStatus;
    private OrderPaymentDetails payment = new OrderPaymentDetails();
    /** Canonical lifecycle value from OrderStatus; String preserves compatibility with existing records. */
    private String orderStatus;

    private OrderAddressSnapshot shippingAddress;
    private OrderAddressSnapshot billingAddress;
    private List<OrderStatusEvent> statusHistory = new ArrayList<>();
    /** True while the order is holding quantities deducted from catalog stock. */
    private Boolean stockReserved = true;

    private LocalDateTime placedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Integer version;

    public Order() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public String getCheckoutGroupId() { return checkoutGroupId; }
    public void setCheckoutGroupId(String checkoutGroupId) { this.checkoutGroupId = checkoutGroupId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public OrderCustomerSnapshot getCustomerSnapshot() { return customerSnapshot; }
    public void setCustomerSnapshot(OrderCustomerSnapshot customerSnapshot) { this.customerSnapshot = customerSnapshot; }
    public String getSellerId() { return sellerId; }
    public void setSellerId(String sellerId) { this.sellerId = sellerId; }
    public OrderSellerSnapshot getSellerSnapshot() { return sellerSnapshot; }
    public void setSellerSnapshot(OrderSellerSnapshot sellerSnapshot) { this.sellerSnapshot = sellerSnapshot; }
    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items != null ? items : new ArrayList<>(); }
    public Double getSubtotalAmount() { return subtotalAmount; }
    public void setSubtotalAmount(Double subtotalAmount) { this.subtotalAmount = subtotalAmount; }
    public Double getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(Double discountAmount) { this.discountAmount = discountAmount; }
    public Double getShippingAmount() { return shippingAmount; }
    public void setShippingAmount(Double shippingAmount) { this.shippingAmount = shippingAmount; }
    public Double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Double totalAmount) { this.totalAmount = totalAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public OrderPaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(OrderPaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public OrderPaymentState getPaymentState() { return paymentState; }
    public void setPaymentState(OrderPaymentState paymentState) { this.paymentState = paymentState; }
    public Double getAdvancePercentage() { return advancePercentage; }
    public void setAdvancePercentage(Double advancePercentage) { this.advancePercentage = advancePercentage; }
    public Double getAdvanceAmountDue() { return advanceAmountDue; }
    public void setAdvanceAmountDue(Double advanceAmountDue) { this.advanceAmountDue = advanceAmountDue; }
    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }
    /** Call only after a trusted payment confirmation; the input is cumulative confirmed money received. */
    public void recordConfirmedPayment(double cumulativeAmountPaid) {
        if (paymentMethod == null) throw new IllegalStateException("Payment method is missing");
        double total = totalAmount == null ? 0.0 : totalAmount;
        double previousPaid = payment == null || payment.getAmountPaid() == null ? 0.0 : payment.getAmountPaid();
        if (cumulativeAmountPaid < previousPaid) {
            throw new IllegalArgumentException("Confirmed cumulative payment cannot be lower than the amount already recorded");
        }
        double initialDue = advanceAmountDue == null ? total : advanceAmountDue;
        if (previousPaid == 0.0 && Math.abs(cumulativeAmountPaid - initialDue) > 0.009) {
            throw new IllegalArgumentException("The first confirmed payment must match the order's advance amount due");
        }
        if (payment == null) payment = new OrderPaymentDetails();
        payment.recordAmounts(total, cumulativeAmountPaid);
        if (cumulativeAmountPaid <= 0.0) {
            paymentStatus = null;
            paymentState = OrderPaymentState.AWAITING_PAYMENT;
        } else if (payment.getRemainingAmount() <= 0.0) {
            paymentStatus = OrderPaymentStatus.PAID.name();
            paymentState = OrderPaymentState.PAYMENT_CONFIRMED;
        } else {
            paymentStatus = OrderPaymentStatus.PARTIALLY_PAID.name();
            paymentState = OrderPaymentState.PAYMENT_CONFIRMED;
        }
    }
    public OrderPaymentDetails getPayment() { return payment; }
    public void setPayment(OrderPaymentDetails payment) { this.payment = payment != null ? payment : new OrderPaymentDetails(); }
    public String getOrderStatus() { return orderStatus; }
    public void setOrderStatus(String orderStatus) { this.orderStatus = orderStatus; }
    public OrderAddressSnapshot getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(OrderAddressSnapshot shippingAddress) { this.shippingAddress = shippingAddress; }
    public OrderAddressSnapshot getBillingAddress() { return billingAddress; }
    public void setBillingAddress(OrderAddressSnapshot billingAddress) { this.billingAddress = billingAddress; }
    public List<OrderStatusEvent> getStatusHistory() { return statusHistory; }
    public void setStatusHistory(List<OrderStatusEvent> statusHistory) { this.statusHistory = statusHistory != null ? statusHistory : new ArrayList<>(); }
    public Boolean getStockReserved() { return stockReserved; }
    public void setStockReserved(Boolean stockReserved) { this.stockReserved = stockReserved; }
    public LocalDateTime getPlacedAt() { return placedAt; }
    public void setPlacedAt(LocalDateTime placedAt) { this.placedAt = placedAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
