package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Role-aware view of the single checkout order document. */
public record OrderResponse(
        String id,
        Integer schemaVersion,
        String orderNumber,
        String checkoutGroupId,
        String idempotencyKey,
        String customerId,
        OrderCustomerSnapshot customerSnapshot,
        OrderAddressSnapshot shippingAddress,
        OrderAddressSnapshot billingAddress,
        List<Item> items,
        List<SellerGroup> sellerFulfillments,
        Order.FinancialSummary financialSummary,
        OrderPaymentDetails payment,
        List<Order.CustomerDecisionWindow> customerDecisionWindows,
        String overallStatus,
        List<StatusEvent> statusHistory,
        LocalDateTime placedAt,
        LocalDateTime cancelledAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer version) {

    public static OrderResponse forCustomer(Order order) { return build(order, null, false); }
    public static OrderResponse forAdmin(Order order) { return build(order, null, true); }
    public static OrderResponse forSeller(Order order, String sellerId) { return build(order, sellerId, false); }

    private static OrderResponse build(Order order, String sellerId, boolean admin) {
        Map<String, Order.SellerFulfillment> allGroups = order.getSellerFulfillments() == null ? Map.of()
                : order.getSellerFulfillments().stream().collect(Collectors.toMap(Order.SellerFulfillment::getId, Function.identity(), (a, b) -> a));
        List<Order.SellerFulfillment> groups = sellerId == null ? List.copyOf(allGroups.values())
                : order.getSellerFulfillments().stream().filter(g -> sellerId.equals(g.getSellerId())).toList();
        java.util.Set<String> allowedIds = sellerId == null ? null : groups.stream()
                .flatMap(g -> g.getItemOrderIds().stream()).collect(Collectors.toSet());
        List<Item> items = order.getItems() == null ? List.of() : order.getItems().stream()
                .filter(i -> allowedIds == null || allowedIds.contains(i.getItemOrderId()))
                .map(i -> Item.from(i, allGroups.get(i.getSellerFulfillmentId()))).toList();
        List<SellerGroup> sellerGroups = groups.stream().map(SellerGroup::from).toList();
        List<StatusEvent> history = order.getStatusHistory() == null ? List.of()
                : order.getStatusHistory().stream()
                .filter(event -> sellerId == null || sellerCanSeeEvent(event, sellerId, allowedIds))
                .map(event -> StatusEvent.from(event, sellerId, admin)).toList();
        OrderCustomerSnapshot customerSnapshot = order.getCustomerSnapshot();
        if (sellerId != null && customerSnapshot != null) {
            OrderCustomerSnapshot limited = new OrderCustomerSnapshot();
            limited.setFirstName(customerSnapshot.getFirstName());
            limited.setLastName(customerSnapshot.getLastName());
            limited.setPhoneNumber(customerSnapshot.getPhoneNumber());
            limited.setEmail(null);
            customerSnapshot = limited;
        }
        return new OrderResponse(order.getId(), admin ? order.getSchemaVersion() : null,
                order.getOrderNumber(), order.getCheckoutGroupId(),
                admin ? order.getIdempotencyKey() : null, admin ? order.getCustomerId() : null,
                customerSnapshot, order.getShippingAddress(), order.getBillingAddress(), items, sellerGroups,
                sellerId == null ? order.getFinancialSummary() : null,
                sellerId == null ? order.getPayment() : null,
                sellerId == null ? order.getCustomerDecisionWindows() : List.of(), order.getOverallStatus(),
                history, order.getPlacedAt(), admin ? order.getCancelledAt() : null, order.getCreatedAt(),
                order.getUpdatedAt(), admin ? order.getVersion() : null);
    }

    private static boolean sellerCanSeeEvent(OrderStatusEvent event, String sellerId, java.util.Set<String> itemIds) {
        if (sellerId.equals(event.getSellerFulfillmentId())) return true;
        return event.getItemOrderIds() != null && itemIds != null
                && event.getItemOrderIds().stream().anyMatch(itemIds::contains);
    }

    public record Item(String itemOrderId, String sellerFulfillmentId, String sellerId, String sellerName,
                       String productId, String categoryId, String variantId, String productCode, String productSku,
                       String variantSku, OrderItem.VariantSnapshot variantSnapshot,
                       Order.ProductSnapshot productSnapshot, Integer quantity,
                       java.math.BigDecimal listPrice, java.math.BigDecimal discountAmount,
                       java.math.BigDecimal discountPercentage, java.math.BigDecimal unitPrice, java.math.BigDecimal taxAmount,
                       java.math.BigDecimal lineTotal, java.math.BigDecimal advanceAllocatedAtCheckout,
                       java.math.BigDecimal codDueAtCheckout, String itemStatus,
                       Order.ItemCancellation cancellation) {
        private static Item from(OrderItem item, Order.SellerFulfillment group) {
            return new Item(item.getItemOrderId(), item.getSellerFulfillmentId(), item.getSellerId(),
                    group == null || group.getSellerSnapshot() == null ? null : group.getSellerSnapshot().getTradeName(),
                    item.getProductId(), item.getCategoryId(), item.getVariantId(), item.getProductCode(), item.getProductSku(),
                    item.getVariantSku(), item.getVariantSnapshot(), item.getProductSnapshot(), item.getQuantity(), item.getListPrice(),
                    item.getDiscountAmount(), item.getDiscountPercentage(), item.getUnitPrice(), item.getTaxAmount(), item.getLineTotal(),
                    item.getAdvanceAllocatedAtCheckout(), item.getCodDueAtCheckout(), item.getItemStatus(),
                    item.getCancellation());
        }
    }

    public record SellerGroup(String id, String sellerId, OrderSellerSnapshot sellerSnapshot,
                              List<String> itemOrderIds, String status, LocalDateTime responseDeadlineAt,
                              LocalDateTime acceptedAt, LocalDateTime dispatchSlaDeadlineAt,
                              LocalDateTime dispatchedAt, List<Order.Shipment> shipments,
                              Order.DelayReview delayReview) {
        private static SellerGroup from(Order.SellerFulfillment value) {
            return new SellerGroup(value.getId(), value.getSellerId(), value.getSellerSnapshot(),
                    value.getItemOrderIds(), value.getStatus(), value.getResponseDeadlineAt(), value.getAcceptedAt(),
                    value.getDispatchSlaDeadlineAt(), value.getDispatchedAt(), value.getShipments(), value.getDelayReview());
        }
    }

    public record StatusEvent(String status, String sellerFulfillmentId, List<String> itemOrderIds,
                              LocalDateTime occurredAt, String actor, String note) {
        private static StatusEvent from(OrderStatusEvent event, String sellerId, boolean admin) {
            return new StatusEvent(event.getStatus(), event.getSellerFulfillmentId(), event.getItemOrderIds(),
                    event.getOccurredAt(), admin ? event.getActor() : null, admin ? event.getNote() : null);
        }
    }
}
