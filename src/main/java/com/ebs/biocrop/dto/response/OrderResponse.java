package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.OrderAddressSnapshot;
import com.ebs.biocrop.entity.OrderItem;
import com.ebs.biocrop.entity.OrderPaymentDetails;
import com.ebs.biocrop.service.OrderStatusPresentation;

import java.time.LocalDateTime;
import java.util.List;

/** Order view that excludes database ownership, stock reservation and reconciliation internals. */
public record OrderResponse(
        String id,
        String orderNumber,
        String orderStatus,
        String paymentMethod,
        String paymentState,
        Double advancePercentage,
        Double advanceAmountDue,
        String paymentStatus,
        PaymentSummary payment,
        List<Item> items,
        Double subtotalAmount,
        Double discountAmount,
        Double shippingAmount,
        Double totalAmount,
        String currency,
        OrderAddressSnapshot shippingAddress,
        OrderAddressSnapshot billingAddress,
        LocalDateTime placedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String customerName,
        String customerPhoneNumber) {

    public static OrderResponse forCustomer(Order order) { return from(order, false); }
    public static OrderResponse forSeller(Order order) { return from(order, true); }

    private static OrderResponse from(Order order, boolean sellerView) {
        OrderPaymentDetails details = order.getPayment();
        PaymentSummary summary = details == null ? null : new PaymentSummary(
                safe(details.getAmountPaid()), safe(details.getAdvanceAmount()), safe(details.getRemainingAmount()),
                safe(details.getPaidPercentage()), safe(details.getRemainingPercentage()));
        String displayStatus = sellerView
                ? OrderStatusPresentation.forSeller(order.getOrderStatus())
                : OrderStatusPresentation.forCustomer(order.getOrderStatus());
        return new OrderResponse(order.getId(), order.getOrderNumber(), displayStatus,
                order.getPaymentMethod() == null ? null : order.getPaymentMethod().name(),
                order.getPaymentState() == null ? null : order.getPaymentState().name(),
                order.getAdvancePercentage(), order.getAdvanceAmountDue(), order.getPaymentStatus(), summary,
                order.getItems() == null ? List.of() : order.getItems().stream().map(Item::from).toList(),
                order.getSubtotalAmount(), order.getDiscountAmount(), order.getShippingAmount(),
                order.getTotalAmount(), order.getCurrency(), order.getShippingAddress(), order.getBillingAddress(),
                order.getPlacedAt(), order.getCreatedAt(), order.getUpdatedAt(),
                sellerView && order.getCustomerSnapshot() != null ? order.getCustomerSnapshot().getName() : null,
                sellerView && order.getCustomerSnapshot() != null ? order.getCustomerSnapshot().getPhoneNumber() : null);
    }

    private static Double safe(Double value) { return value == null ? 0.0 : value; }

    public record PaymentSummary(Double amountPaid, Double advanceAmount, Double remainingAmount,
                                 Double paidPercentage, Double remainingPercentage) { }

    public record Item(String productId, String variantId, String title, String vendor, String image,
                       String variant, Integer quantity, Double listPrice, Double unitPrice, Double lineTotal) {
        private static Item from(OrderItem item) {
            return new Item(item.getProductId(), item.getVariantId(), item.getTitle(), item.getVendor(),
                    item.getImage(), item.getVariant(), item.getQuantity(), item.getListPrice(),
                    item.getUnitPrice(), item.getLineTotal());
        }
    }
}
