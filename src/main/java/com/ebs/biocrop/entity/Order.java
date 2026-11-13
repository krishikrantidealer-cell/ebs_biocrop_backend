package com.ebs.biocrop.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** One complete customer checkout, with seller operations embedded in the same document. */
@Document(collection = "orders")
public class Order {
    @Id
    private String id;
    @Field(write = Field.Write.ALWAYS)
    private Integer schemaVersion = 2;
    @Field(write = Field.Write.ALWAYS)
    private String orderNumber;
    @Field(write = Field.Write.ALWAYS)
    private String checkoutGroupId;
    @Field(write = Field.Write.ALWAYS)
    private String idempotencyKey;
    @Field(write = Field.Write.ALWAYS)
    private String customerId;
    @Field(write = Field.Write.ALWAYS)
    private OrderCustomerSnapshot customerSnapshot = new OrderCustomerSnapshot();
    @Field(write = Field.Write.ALWAYS)
    private OrderAddressSnapshot shippingAddress;
    @Field(write = Field.Write.ALWAYS)
    private OrderAddressSnapshot billingAddress;
    @Field(write = Field.Write.ALWAYS)
    private List<OrderItem> items = new ArrayList<>();
    @Field(write = Field.Write.ALWAYS)
    private List<SellerFulfillment> sellerFulfillments = new ArrayList<>();
    @Field(write = Field.Write.ALWAYS)
    private FinancialSummary financialSummary = new FinancialSummary();
    @Field(write = Field.Write.ALWAYS)
    private OrderPaymentDetails payment = new OrderPaymentDetails();
    @Field(write = Field.Write.ALWAYS)
    private List<CustomerDecisionWindow> customerDecisionWindows = new ArrayList<>();
    @Field(write = Field.Write.ALWAYS)
    private String overallStatus = "AWAITING_ADVANCE_PAYMENT";
    @Field(write = Field.Write.ALWAYS)
    private List<OrderStatusEvent> statusHistory = new ArrayList<>();
    @Field(write = Field.Write.ALWAYS)
    private LocalDateTime placedAt;
    @Field(write = Field.Write.ALWAYS)
    private LocalDateTime cancelledAt;
    @Field(write = Field.Write.ALWAYS)
    private LocalDateTime createdAt;
    @Field(write = Field.Write.ALWAYS)
    private LocalDateTime updatedAt;
    @Version
    @Field(write = Field.Write.ALWAYS)
    private Integer version;
    @Field(write = Field.Write.ALWAYS)
    private Boolean stockReserved = false;

    public Order() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion == null ? 2 : schemaVersion; }
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public String getCheckoutGroupId() { return checkoutGroupId; }
    public void setCheckoutGroupId(String checkoutGroupId) { this.checkoutGroupId = checkoutGroupId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public OrderCustomerSnapshot getCustomerSnapshot() { return customerSnapshot; }
    public void setCustomerSnapshot(OrderCustomerSnapshot value) { customerSnapshot = value == null ? new OrderCustomerSnapshot() : value; }
    public OrderAddressSnapshot getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(OrderAddressSnapshot shippingAddress) { this.shippingAddress = shippingAddress; }
    public OrderAddressSnapshot getBillingAddress() { return billingAddress; }
    public void setBillingAddress(OrderAddressSnapshot billingAddress) { this.billingAddress = billingAddress; }
    public List<OrderItem> getItems() {
        if (items == null) items = new ArrayList<>();
        return items;
    }
    public void setItems(List<OrderItem> items) { this.items = items == null ? new ArrayList<>() : items; }
    public List<SellerFulfillment> getSellerFulfillments() {
        if (sellerFulfillments == null) sellerFulfillments = new ArrayList<>();
        return sellerFulfillments;
    }
    public void setSellerFulfillments(List<SellerFulfillment> values) { sellerFulfillments = values == null ? new ArrayList<>() : values; }
    public FinancialSummary getFinancialSummary() { return financialSummary; }
    public void setFinancialSummary(FinancialSummary value) { financialSummary = value == null ? new FinancialSummary() : value; }
    public OrderPaymentDetails getPayment() { return payment; }
    public void setPayment(OrderPaymentDetails value) { payment = value == null ? new OrderPaymentDetails() : value; }
    public List<CustomerDecisionWindow> getCustomerDecisionWindows() {
        if (customerDecisionWindows == null) customerDecisionWindows = new ArrayList<>();
        return customerDecisionWindows;
    }
    public void setCustomerDecisionWindows(List<CustomerDecisionWindow> values) { customerDecisionWindows = values == null ? new ArrayList<>() : values; }
    public String getOverallStatus() { return overallStatus; }
    public void setOverallStatus(String overallStatus) { this.overallStatus = overallStatus; }
    public List<OrderStatusEvent> getStatusHistory() {
        if (statusHistory == null) statusHistory = new ArrayList<>();
        return statusHistory;
    }
    public void setStatusHistory(List<OrderStatusEvent> values) { statusHistory = values == null ? new ArrayList<>() : values; }
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
    public Boolean getStockReserved() { return stockReserved; }
    public void setStockReserved(Boolean stockReserved) { this.stockReserved = stockReserved == null ? false : stockReserved; }

    /** Temporary in-memory adapters for older lifecycle/response code; these are never persisted. */
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public String getOrderStatus() { return overallStatus; }
    public void setOrderStatus(String value) { overallStatus = value; }
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public String getSellerId() {
        return sellerFulfillments.isEmpty() ? null : sellerFulfillments.getFirst().getSellerId();
    }
    public void setSellerId(String value) { }
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public OrderSellerSnapshot getSellerSnapshot() {
        return sellerFulfillments.isEmpty() ? null : sellerFulfillments.getFirst().getSellerSnapshot();
    }
    public void setSellerSnapshot(OrderSellerSnapshot value) { }
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public Double getSubtotalAmount() {
        return financialSummary.getGrossMerchandiseValue().doubleValue();
    }
    public void setSubtotalAmount(Double value) { financialSummary.setGrossMerchandiseValue(value == null ? null : java.math.BigDecimal.valueOf(value)); }
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public Double getDiscountAmount() { return financialSummary.getDiscountTotal().doubleValue(); }
    public void setDiscountAmount(Double value) { financialSummary.setDiscountTotal(value == null ? null : java.math.BigDecimal.valueOf(value)); }
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public Double getShippingAmount() { return financialSummary.getDeliveryCharges() == null ? null : financialSummary.getDeliveryCharges().doubleValue(); }
    public void setShippingAmount(Double value) { financialSummary.setDeliveryCharges(value == null ? null : java.math.BigDecimal.valueOf(value)); }
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public Double getTotalAmount() { return financialSummary.getCurrentOrderTotal().doubleValue(); }
    public void setTotalAmount(Double value) { financialSummary.setCurrentOrderTotal(value == null ? null : java.math.BigDecimal.valueOf(value)); }
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public String getCurrency() { return financialSummary.getCurrency(); }
    public void setCurrency(String value) { financialSummary.setCurrency(value); }
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public OrderPaymentMethod getPaymentMethod() {
        if (payment == null || payment.getPaymentType() == null) return OrderPaymentMethod.PARTIAL;
        if ("PREPAID".equalsIgnoreCase(payment.getPaymentType())) return OrderPaymentMethod.PREPAID;
        if ("COD".equalsIgnoreCase(payment.getPaymentType())) return OrderPaymentMethod.COD;
        return OrderPaymentMethod.PARTIAL;
    }
    public void setPaymentMethod(OrderPaymentMethod value) { }
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public OrderPaymentState getPaymentState() {
        return "AWAITING_ADVANCE_PAYMENT".equals(overallStatus) ? OrderPaymentState.AWAITING_PAYMENT : OrderPaymentState.PAYMENT_CONFIRMED;
    }
    public void setPaymentState(OrderPaymentState value) { }
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public Double getAdvancePercentage() { return payment.getAdvancePercentage().doubleValue(); }
    public void setAdvancePercentage(Double value) { payment.setAdvancePercentage(value == null ? null : java.math.BigDecimal.valueOf(value)); }
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public Double getAdvanceAmountDue() { return financialSummary.getAdvanceAmountDue().doubleValue(); }
    public void setAdvanceAmountDue(Double value) { financialSummary.setAdvanceAmountDue(value == null ? null : java.math.BigDecimal.valueOf(value)); }
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public String getPaymentStatus() { return payment.getStatus(); }
    public void setPaymentStatus(String value) { payment.setStatus(value); }
    public static class SellerFulfillment {
        @Field(name = "id", write = Field.Write.ALWAYS) private String fulfillmentId;
        @Field(write = Field.Write.ALWAYS) private String sellerId;
        @Field(write = Field.Write.ALWAYS) private OrderSellerSnapshot sellerSnapshot = new OrderSellerSnapshot();
        @Field(write = Field.Write.ALWAYS) private List<String> itemOrderIds = new ArrayList<>();
        @Field(write = Field.Write.ALWAYS) private String status = "AWAITING_SELLER_RESPONSE";
        @Field(write = Field.Write.ALWAYS) private LocalDateTime responseDeadlineAt;
        @Field(write = Field.Write.ALWAYS) private LocalDateTime acceptedAt;
        @Field(write = Field.Write.ALWAYS) private LocalDateTime dispatchSlaDeadlineAt;
        @Field(write = Field.Write.ALWAYS) private LocalDateTime dispatchedAt;
        @Field(write = Field.Write.ALWAYS) private List<Shipment> shipments = new ArrayList<>();
        @Field(write = Field.Write.ALWAYS) private DelayReview delayReview = new DelayReview();
        public String getId() { return fulfillmentId; }
        public void setId(String value) { fulfillmentId = value; }
        public String getFulfillmentId() { return fulfillmentId; }
        public void setFulfillmentId(String value) { fulfillmentId = value; }
        public String getSellerId() { return sellerId; }
        public void setSellerId(String value) { sellerId = value; }
        public OrderSellerSnapshot getSellerSnapshot() { return sellerSnapshot; }
        public void setSellerSnapshot(OrderSellerSnapshot value) { sellerSnapshot = value == null ? new OrderSellerSnapshot() : value; }
        public List<String> getItemOrderIds() { return itemOrderIds; }
        public void setItemOrderIds(List<String> values) { itemOrderIds = values == null ? new ArrayList<>() : values; }
        public String getStatus() { return status; }
        public void setStatus(String value) { status = value; }
        public LocalDateTime getResponseDeadlineAt() { return responseDeadlineAt; }
        public void setResponseDeadlineAt(LocalDateTime value) { responseDeadlineAt = value; }
        public LocalDateTime getAcceptedAt() { return acceptedAt; }
        public void setAcceptedAt(LocalDateTime value) { acceptedAt = value; }
        public LocalDateTime getDispatchSlaDeadlineAt() { return dispatchSlaDeadlineAt; }
        public void setDispatchSlaDeadlineAt(LocalDateTime value) { dispatchSlaDeadlineAt = value; }
        public LocalDateTime getDispatchedAt() { return dispatchedAt; }
        public void setDispatchedAt(LocalDateTime value) { dispatchedAt = value; }
        public List<Shipment> getShipments() { return shipments; }
        public void setShipments(List<Shipment> values) { shipments = values == null ? new ArrayList<>() : values; }
        public DelayReview getDelayReview() { return delayReview; }
        public void setDelayReview(DelayReview value) { delayReview = value == null ? new DelayReview() : value; }
    }

    public static class Shipment {
        @Field(write = Field.Write.ALWAYS) private String shipmentId;
        @Field(write = Field.Write.ALWAYS) private List<String> itemOrderIds = new ArrayList<>();
        @Field(write = Field.Write.ALWAYS) private String status = "NOT_CREATED";
        @Field(write = Field.Write.ALWAYS) private String carrier;
        @Field(write = Field.Write.ALWAYS) private String trackingNumber;
        @Field(write = Field.Write.ALWAYS) private String labelReference;
        @Field(write = Field.Write.ALWAYS) private LocalDateTime labelCreatedAt;
        @Field(write = Field.Write.ALWAYS) private LocalDateTime pickupConfirmedAt;
        @Field(write = Field.Write.ALWAYS) private LocalDateTime lastTrackingUpdateAt;
        @Field(write = Field.Write.ALWAYS) private String lastTrackingDescription;
        @Field(write = Field.Write.ALWAYS) private LocalDateTime deliveredAt;
        public String getShipmentId() { return shipmentId; }
        public void setShipmentId(String value) { shipmentId = value; }
        public List<String> getItemOrderIds() { return itemOrderIds; }
        public void setItemOrderIds(List<String> values) { itemOrderIds = values == null ? new ArrayList<>() : values; }
        public String getStatus() { return status; }
        public void setStatus(String value) { status = value; }
        public String getCarrier() { return carrier; }
        public void setCarrier(String value) { carrier = value; }
        public String getTrackingNumber() { return trackingNumber; }
        public void setTrackingNumber(String value) { trackingNumber = value; }
        public String getLabelReference() { return labelReference; }
        public void setLabelReference(String value) { labelReference = value; }
        public LocalDateTime getLabelCreatedAt() { return labelCreatedAt; }
        public void setLabelCreatedAt(LocalDateTime value) { labelCreatedAt = value; }
        public LocalDateTime getPickupConfirmedAt() { return pickupConfirmedAt; }
        public void setPickupConfirmedAt(LocalDateTime value) { pickupConfirmedAt = value; }
        public LocalDateTime getLastTrackingUpdateAt() { return lastTrackingUpdateAt; }
        public void setLastTrackingUpdateAt(LocalDateTime value) { lastTrackingUpdateAt = value; }
        public String getLastTrackingDescription() { return lastTrackingDescription; }
        public void setLastTrackingDescription(String value) { lastTrackingDescription = value; }
        public LocalDateTime getDeliveredAt() { return deliveredAt; }
        public void setDeliveredAt(LocalDateTime value) { deliveredAt = value; }
    }

    public static class DelayReview {
        @Field(write = Field.Write.ALWAYS) private String status = "NOT_OPEN";
        @Field(write = Field.Write.ALWAYS) private String reason;
        @Field(write = Field.Write.ALWAYS) private String openedBy;
        @Field(write = Field.Write.ALWAYS) private LocalDateTime openedAt;
        @Field(write = Field.Write.ALWAYS) private String resolution;
        @Field(write = Field.Write.ALWAYS) private String resolutionNote;
        @Field(write = Field.Write.ALWAYS) private String resolvedBy;
        @Field(write = Field.Write.ALWAYS) private LocalDateTime resolvedAt;
        public String getStatus() { return status; }
        public void setStatus(String value) { status = value; }
        public String getReason() { return reason; }
        public void setReason(String value) { reason = value; }
        public String getOpenedBy() { return openedBy; }
        public void setOpenedBy(String value) { openedBy = value; }
        public LocalDateTime getOpenedAt() { return openedAt; }
        public void setOpenedAt(LocalDateTime value) { openedAt = value; }
        public String getResolution() { return resolution; }
        public void setResolution(String value) { resolution = value; }
        public String getResolutionNote() { return resolutionNote; }
        public void setResolutionNote(String value) { resolutionNote = value; }
        public String getResolvedBy() { return resolvedBy; }
        public void setResolvedBy(String value) { resolvedBy = value; }
        public LocalDateTime getResolvedAt() { return resolvedAt; }
        public void setResolvedAt(LocalDateTime value) { resolvedAt = value; }
    }

    public static class FinancialSummary {
        @Field(write = Field.Write.ALWAYS) private String currency = "INR";
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal grossMerchandiseValue = java.math.BigDecimal.ZERO;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal discountTotal = java.math.BigDecimal.ZERO;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal taxTotal;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal deliveryCharges = java.math.BigDecimal.ZERO;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal codCharges = java.math.BigDecimal.ZERO;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal originalOrderTotal = java.math.BigDecimal.ZERO;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal advanceAmountDue = java.math.BigDecimal.ZERO;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal onlineAmountCaptured = java.math.BigDecimal.ZERO;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal netOnlineAmountRetained = java.math.BigDecimal.ZERO;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal codDue = java.math.BigDecimal.ZERO;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal cancelledAmount = java.math.BigDecimal.ZERO;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal currentOrderTotal = java.math.BigDecimal.ZERO;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal refundedTotal = java.math.BigDecimal.ZERO;
        public String getCurrency() { return currency; }
        public void setCurrency(String value) { currency = value; }
        public java.math.BigDecimal getGrossMerchandiseValue() { return grossMerchandiseValue; }
        public void setGrossMerchandiseValue(java.math.BigDecimal value) { grossMerchandiseValue = zero(value); }
        public java.math.BigDecimal getDiscountTotal() { return discountTotal; }
        public void setDiscountTotal(java.math.BigDecimal value) { discountTotal = zero(value); }
        public java.math.BigDecimal getTaxTotal() { return taxTotal; }
        public void setTaxTotal(java.math.BigDecimal value) { taxTotal = value; }
        public java.math.BigDecimal getDeliveryCharges() { return deliveryCharges; }
        public void setDeliveryCharges(java.math.BigDecimal value) { deliveryCharges = zero(value); }
        public java.math.BigDecimal getCodCharges() { return codCharges; }
        public void setCodCharges(java.math.BigDecimal value) { codCharges = zero(value); }
        public java.math.BigDecimal getOriginalOrderTotal() { return originalOrderTotal; }
        public void setOriginalOrderTotal(java.math.BigDecimal value) { originalOrderTotal = zero(value); }
        public java.math.BigDecimal getAdvanceAmountDue() { return advanceAmountDue; }
        public void setAdvanceAmountDue(java.math.BigDecimal value) { advanceAmountDue = zero(value); }
        public java.math.BigDecimal getOnlineAmountCaptured() { return onlineAmountCaptured; }
        public void setOnlineAmountCaptured(java.math.BigDecimal value) { onlineAmountCaptured = zero(value); }
        public java.math.BigDecimal getNetOnlineAmountRetained() { return netOnlineAmountRetained; }
        public void setNetOnlineAmountRetained(java.math.BigDecimal value) { netOnlineAmountRetained = zero(value); }
        public java.math.BigDecimal getCodDue() { return codDue; }
        public void setCodDue(java.math.BigDecimal value) { codDue = zero(value); }
        public java.math.BigDecimal getCancelledAmount() { return cancelledAmount; }
        public void setCancelledAmount(java.math.BigDecimal value) { cancelledAmount = zero(value); }
        public java.math.BigDecimal getCurrentOrderTotal() { return currentOrderTotal; }
        public void setCurrentOrderTotal(java.math.BigDecimal value) { currentOrderTotal = zero(value); }
        public java.math.BigDecimal getRefundedTotal() { return refundedTotal; }
        public void setRefundedTotal(java.math.BigDecimal value) { refundedTotal = zero(value); }
        private static java.math.BigDecimal zero(java.math.BigDecimal value) { return value == null ? java.math.BigDecimal.ZERO : value; }
    }

    public static class CustomerDecisionWindow {
        @Field(write = Field.Write.ALWAYS) private String decisionWindowId;
        @Field(write = Field.Write.ALWAYS) private String triggerId;
        @Field(write = Field.Write.ALWAYS) private String sellerFulfillmentId;
        @Field(write = Field.Write.ALWAYS) private List<String> affectedItemOrderIds = new ArrayList<>();
        @Field(write = Field.Write.ALWAYS) private LocalDateTime openedAt;
        @Field(write = Field.Write.ALWAYS) private LocalDateTime deadlineAt;
        @Field(write = Field.Write.ALWAYS) private String status = "NOT_OPEN";
        @Field(write = Field.Write.ALWAYS) private String resolution;
        @Field(write = Field.Write.ALWAYS) private LocalDateTime resolvedAt;
        public String getDecisionWindowId() { return decisionWindowId; }
        public void setDecisionWindowId(String value) { decisionWindowId = value; }
        public String getTriggerId() { return triggerId; }
        public void setTriggerId(String value) { triggerId = value; }
        public String getSellerFulfillmentId() { return sellerFulfillmentId; }
        public void setSellerFulfillmentId(String value) { sellerFulfillmentId = value; }
        public List<String> getAffectedItemOrderIds() { return affectedItemOrderIds; }
        public void setAffectedItemOrderIds(List<String> values) { affectedItemOrderIds = values == null ? new ArrayList<>() : values; }
        public LocalDateTime getOpenedAt() { return openedAt; }
        public void setOpenedAt(LocalDateTime value) { openedAt = value; }
        public LocalDateTime getDeadlineAt() { return deadlineAt; }
        public void setDeadlineAt(LocalDateTime value) { deadlineAt = value; }
        public String getStatus() { return status; }
        public void setStatus(String value) { status = value; }
        public String getResolution() { return resolution; }
        public void setResolution(String value) { resolution = value; }
        public LocalDateTime getResolvedAt() { return resolvedAt; }
        public void setResolvedAt(LocalDateTime value) { resolvedAt = value; }
    }

    public static class Refund {
        @Field(write = Field.Write.ALWAYS) private String refundId;
        @Field(write = Field.Write.ALWAYS) private List<String> itemOrderIds = new ArrayList<>();
        @Field(write = Field.Write.ALWAYS) private String providerRefundId;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal amount = java.math.BigDecimal.ZERO;
        @Field(write = Field.Write.ALWAYS) private String reason;
        @Field(write = Field.Write.ALWAYS) private String status = "NOT_REQUESTED";
        @Field(write = Field.Write.ALWAYS) private LocalDateTime occurredAt;
        public String getRefundId() { return refundId; }
        public void setRefundId(String value) { refundId = value; }
        public List<String> getItemOrderIds() { return itemOrderIds; }
        public void setItemOrderIds(List<String> values) { itemOrderIds = values == null ? new ArrayList<>() : values; }
        public String getProviderRefundId() { return providerRefundId; }
        public void setProviderRefundId(String value) { providerRefundId = value; }
        public java.math.BigDecimal getAmount() { return amount; }
        public void setAmount(java.math.BigDecimal value) { amount = value == null ? java.math.BigDecimal.ZERO : value; }
        public String getReason() { return reason; }
        public void setReason(String value) { reason = value; }
        public String getStatus() { return status; }
        public void setStatus(String value) { status = value; }
        public LocalDateTime getOccurredAt() { return occurredAt; }
        public void setOccurredAt(LocalDateTime value) { occurredAt = value; }
    }

    public static class ProductSnapshot {
        @Field(write = Field.Write.ALWAYS) private String title;
        @Field(write = Field.Write.ALWAYS) private String variantLabel;
        @Field(write = Field.Write.ALWAYS) private String hsnCode;
        @Field(write = Field.Write.ALWAYS) private java.math.BigDecimal taxRatePercentage;
        @Field(write = Field.Write.ALWAYS) private String imageObjectName;
        @Field(write = Field.Write.ALWAYS) private String categoryName;
        public String getTitle() { return title; }
        public void setTitle(String value) { title = value; }
        public String getVariantLabel() { return variantLabel; }
        public void setVariantLabel(String value) { variantLabel = value; }
        public String getHsnCode() { return hsnCode; }
        public void setHsnCode(String value) { hsnCode = value; }
        public java.math.BigDecimal getTaxRatePercentage() { return taxRatePercentage; }
        public void setTaxRatePercentage(java.math.BigDecimal value) { taxRatePercentage = value; }
        public String getImageObjectName() { return imageObjectName; }
        public void setImageObjectName(String value) { imageObjectName = value; }
        public String getCategoryName() { return categoryName; }
        public void setCategoryName(String value) { categoryName = value; }
    }

    public static class ItemCancellation {
        @Field(write = Field.Write.ALWAYS) private String status = "NOT_REQUESTED";
        @Field(write = Field.Write.ALWAYS) private String cancelledBy;
        @Field(write = Field.Write.ALWAYS) private String reason;
        @Field(write = Field.Write.ALWAYS) private LocalDateTime requestedAt;
        @Field(write = Field.Write.ALWAYS) private LocalDateTime cancelledAt;
        @Field(write = Field.Write.ALWAYS) private String carrierVoidStatus = "NOT_REQUIRED";
        public String getStatus() { return status; }
        public void setStatus(String value) { status = value; }
        public String getCancelledBy() { return cancelledBy; }
        public void setCancelledBy(String value) { cancelledBy = value; }
        public String getReason() { return reason; }
        public void setReason(String value) { reason = value; }
        public LocalDateTime getRequestedAt() { return requestedAt; }
        public void setRequestedAt(LocalDateTime value) { requestedAt = value; }
        public LocalDateTime getCancelledAt() { return cancelledAt; }
        public void setCancelledAt(LocalDateTime value) { cancelledAt = value; }
        public String getCarrierVoidStatus() { return carrierVoidStatus; }
        public void setCarrierVoidStatus(String value) { carrierVoidStatus = value; }
    }
}
