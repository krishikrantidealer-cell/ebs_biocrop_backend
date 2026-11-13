package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.OrderItem;
import com.ebs.biocrop.entity.OrderStatusEvent;
import com.ebs.biocrop.entity.OrderPaymentTransaction;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.service.OrderLifecycleService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.websocket.WebSocketService;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Supports the safe pre-payment cancellation operation in the initial order slice. */
@Service
public class OrderLifecycleServiceImpl implements OrderLifecycleService {
    private static final BigDecimal ADVANCE_PERCENTAGE = new BigDecimal("10.00");
    private final OrderRepository orders;
    private final MongoTemplate mongoTemplate;
    private final RedisJsonCache redisCache;
    private final WebSocketService webSocketService;
    private final com.ebs.biocrop.service.ShiprocketService shiprocketService;

    public OrderLifecycleServiceImpl(OrderRepository orders, MongoTemplate mongoTemplate,
                                     RedisJsonCache redisCache, WebSocketService webSocketService) {
        this(orders, mongoTemplate, redisCache, webSocketService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrderLifecycleServiceImpl(OrderRepository orders, MongoTemplate mongoTemplate,
                                     RedisJsonCache redisCache, WebSocketService webSocketService,
                                     @org.springframework.beans.factory.annotation.Autowired(required = false)
                                     com.ebs.biocrop.service.ShiprocketService shiprocketService) {
        this.orders = orders;
        this.mongoTemplate = mongoTemplate;
        this.redisCache = redisCache;
        this.webSocketService = webSocketService;
        this.shiprocketService = shiprocketService;
    }

    @Override
    @Transactional
    public Order cancelForCustomer(String customerId, String orderId) {
        Order order = orders.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        return cancel(order, "CUSTOMER");
    }

    @Override
    @Transactional
    public Order cancelForAdmin(String adminId, String orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        return cancel(order, adminId);
    }

    /**
     * Provider-neutral domain transition. A future gateway adapter must verify the provider signature
     * and captured status before calling this method; this method is not exposed as a public API.
     */
    @Override
    @Transactional
    public Order recordAdvancePaymentCaptured(String orderId, String providerPaymentReference, BigDecimal capturedAmount) {
        if (providerPaymentReference == null || providerPaymentReference.isBlank() || capturedAmount == null
                || capturedAmount.signum() <= 0) {
            throw new AppException("A verified payment reference and positive captured amount are required", HttpStatus.BAD_REQUEST);
        }
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        if (order.getPayment() == null || order.getFinancialSummary() == null) {
            throw new AppException("Order payment details are incomplete", HttpStatus.CONFLICT);
        }
        if (order.getPayment().getTransactions() == null) order.getPayment().setTransactions(new ArrayList<>());
        boolean prepaid = "PREPAID".equalsIgnoreCase(order.getPayment().getPaymentType());
        String transactionType = prepaid ? "PREPAID_CAPTURE" : "ADVANCE_CAPTURE";
        boolean replay = order.getPayment().getTransactions().stream().anyMatch(tx ->
                Objects.equals(providerPaymentReference, tx.getProviderPaymentId())
                        && transactionType.equals(tx.getType()) && "CAPTURED".equals(tx.getStatus()));
        if (replay) return order;
        if (!"AWAITING_ADVANCE_PAYMENT".equals(order.getOverallStatus())
                || !"AWAITING_ADVANCE_PAYMENT".equals(order.getPayment().getStatus())) {
            throw new AppException("Order is not awaiting advance payment", HttpStatus.CONFLICT);
        }
        BigDecimal expected = order.getPayment().getAdvanceAmountDue().setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal captured = capturedAmount.setScale(2, java.math.RoundingMode.HALF_UP);
        if (expected.signum() <= 0 || expected.compareTo(captured) != 0) {
            throw new AppException("Captured amount does not match the required 10% advance", HttpStatus.CONFLICT);
        }

        LocalDateTime now = LocalDateTime.now();
        OrderPaymentTransaction transaction = new OrderPaymentTransaction();
        transaction.setTransactionId(java.util.UUID.randomUUID().toString());
        transaction.setType(transactionType);
        transaction.setAmount(captured);
        transaction.setStatus("CAPTURED");
        transaction.setProvider(order.getPayment().getProvider());
        transaction.setProviderPaymentId(providerPaymentReference);
        transaction.setOccurredAt(now);
        if (order.getPayment().getTransactions() == null) order.getPayment().setTransactions(new ArrayList<>());
        order.getPayment().getTransactions().add(transaction);
        order.getPayment().setStatus(prepaid ? "PAID" : "PARTIALLY_PAID");
        order.getPayment().setAmountPaid(captured);
        order.getPayment().setAdvanceAmountPaid(captured);
        order.getPayment().setRemainingAmount(order.getFinancialSummary().getCodDue());
        BigDecimal paidPercentage = order.getPayment().getAdvancePercentage();
        order.getPayment().setPaidPercentage(paidPercentage);
        order.getPayment().setRemainingPercentage(new BigDecimal("100.00").subtract(paidPercentage));
        order.getFinancialSummary().setOnlineAmountCaptured(captured);
        order.getFinancialSummary().setNetOnlineAmountRetained(captured);

        for (Order.SellerFulfillment fulfillment : order.getSellerFulfillments() == null
                ? List.<Order.SellerFulfillment>of() : order.getSellerFulfillments()) {
            fulfillment.setStatus("AWAITING_SELLER_RESPONSE");
            fulfillment.setResponseDeadlineAt(now.plusHours(12));
            for (OrderItem item : order.getItems() == null ? List.<OrderItem>of() : order.getItems()) {
                if (Objects.equals(fulfillment.getId(), item.getSellerFulfillmentId())) {
                    item.setItemStatus("AWAITING_SELLER_RESPONSE");
                }
            }
        }
        order.setOverallStatus("AWAITING_SELLER_RESPONSE");
        order.setUpdatedAt(now);
        appendHistory(order, prepaid ? "PREPAID_PAYMENT_CAPTURED" : "ADVANCE_PAYMENT_CAPTURED", "SYSTEM",
                prepaid ? "Full online payment confirmed" : "Required partial online payment confirmed");
        return orders.save(order);
    }

    @Override
    @Transactional
    public Order respondToSellerFulfillment(String sellerId, String orderId, String fulfillmentId, String decision) {
        Order order = orders.findByIdAndSellerFulfillmentsSellerId(orderId, sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        if (!"AWAITING_SELLER_RESPONSE".equals(order.getOverallStatus())
                && !"PROCESSING".equals(order.getOverallStatus())) {
            throw new AppException("Order is not accepting seller responses", HttpStatus.CONFLICT);
        }
        if (order.getPayment() == null || order.getPayment().getAmountPaid() == null
                || order.getPayment().getAdvanceAmountDue() == null
                || order.getPayment().getAmountPaid().compareTo(order.getPayment().getAdvanceAmountDue()) < 0) {
            throw new AppException("Seller fulfillment cannot start before the required advance is confirmed", HttpStatus.CONFLICT);
        }
        Order.SellerFulfillment fulfillment = order.getSellerFulfillments().stream()
                .filter(value -> Objects.equals(value.getId(), fulfillmentId)
                        && Objects.equals(value.getSellerId(), sellerId))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Seller fulfillment", "id", fulfillmentId));
        if (!"AWAITING_SELLER_RESPONSE".equals(fulfillment.getStatus())) {
            throw new AppException("This seller fulfillment has already been answered", HttpStatus.CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now();
        if (fulfillment.getResponseDeadlineAt() == null || now.isAfter(fulfillment.getResponseDeadlineAt())) {
            throw new AppException("Seller response window has expired", HttpStatus.CONFLICT);
        }
        String normalized = decision == null ? "" : decision.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"ACCEPT".equals(normalized) && !"REJECT".equals(normalized)) {
            throw new AppException("Decision must be ACCEPT or REJECT", HttpStatus.BAD_REQUEST);
        }
        List<String> affectedItems = fulfillment.getItemOrderIds() == null
                ? new ArrayList<>() : new ArrayList<>(fulfillment.getItemOrderIds());
        if (affectedItems.isEmpty()) throw new AppException("Seller fulfillment has no items", HttpStatus.CONFLICT);

        if ("ACCEPT".equals(normalized)) {
            fulfillment.setStatus("ACCEPTED");
            fulfillment.setAcceptedAt(now);
            fulfillment.setDispatchSlaDeadlineAt(now.plusHours(48));
            for (OrderItem item : order.getItems()) {
                if (affectedItems.contains(item.getItemOrderId())) item.setItemStatus("PROCESSING");
            }
            boolean sellerStillAwaiting = order.getSellerFulfillments().stream()
                    .anyMatch(value -> value != fulfillment && "AWAITING_SELLER_RESPONSE".equals(value.getStatus()));
            order.setOverallStatus(sellerStillAwaiting ? "AWAITING_SELLER_RESPONSE" : "PROCESSING");
        } else {
            fulfillment.setStatus("REJECTED");
            List<OrderItem> rejectedItems = order.getItems().stream()
                    .filter(item -> affectedItems.contains(item.getItemOrderId())).toList();
            releaseStock(rejectedItems);
            for (OrderItem item : order.getItems()) {
                if (affectedItems.contains(item.getItemOrderId())) {
                    item.setItemStatus("CANCELLED_BY_SELLER");
                    markCancelled(item, sellerId, "SELLER_REJECTED");
                }
            }
            applyCancelledAmounts(order, rejectedItems);
            Order.Refund refund = new Order.Refund();
            refund.setRefundId(java.util.UUID.randomUUID().toString());
            refund.setItemOrderIds(affectedItems);
            refund.setAmount(rejectedItems.stream().map(OrderItem::getAdvanceAllocatedAtCheckout).reduce(BigDecimal.ZERO, BigDecimal::add));
            refund.setReason("SELLER_REJECTED_ITEMS");
            refund.setStatus("PENDING_PROVIDER_INTEGRATION");
            refund.setOccurredAt(now);
            if (refund.getAmount().signum() > 0) {
                if (order.getPayment().getRefunds() == null) order.getPayment().setRefunds(new ArrayList<>());
                order.getPayment().getRefunds().add(refund);
                order.getPayment().setStatus("REFUND_PENDING");
                order.getFinancialSummary().setNetOnlineAmountRetained(
                        order.getPayment().getAmountPaid().subtract(pendingOrCompletedRefunds(order)).max(BigDecimal.ZERO));
            }
            Order.CustomerDecisionWindow window = new Order.CustomerDecisionWindow();
            window.setTriggerId("SELLER_REJECTION");
            window.setDecisionWindowId(java.util.UUID.randomUUID().toString());
            window.setSellerFulfillmentId(fulfillment.getId());
            window.setAffectedItemOrderIds(affectedItems);
            window.setOpenedAt(now);
            window.setDeadlineAt(now.plusHours(6));
            window.setStatus("OPEN");
            if (order.getCustomerDecisionWindows() == null) order.setCustomerDecisionWindows(new ArrayList<>());
            order.getCustomerDecisionWindows().add(window);
            order.setOverallStatus("CUSTOMER_DECISION_REQUIRED");
        }
        order.setUpdatedAt(now);
        appendHistory(order, "ACCEPT".equals(normalized) ? "SELLER_ACCEPTED" : "SELLER_REJECTED",
                sellerId, "Seller " + normalized.toLowerCase(java.util.Locale.ROOT) + " fulfillment " + fulfillmentId);
        return orders.save(order);
    }

    @Override
    @Transactional
    public Order markSellerFulfillmentPacked(String sellerId, String orderId, String fulfillmentId) {
        Order order = orders.findByIdAndSellerFulfillmentsSellerId(orderId, sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        Order.SellerFulfillment fulfillment = order.getSellerFulfillments().stream()
                .filter(value -> Objects.equals(value.getId(), fulfillmentId)
                        && Objects.equals(value.getSellerId(), sellerId))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Seller fulfillment", "id", fulfillmentId));
        if ("PACKED".equals(fulfillment.getStatus())) return order;
        if (!"ACCEPTED".equals(fulfillment.getStatus())) {
            throw new AppException("Only an accepted fulfillment can be marked packed", HttpStatus.CONFLICT);
        }
        if (fulfillment.getDispatchSlaDeadlineAt() == null
                || LocalDateTime.now().isAfter(fulfillment.getDispatchSlaDeadlineAt())) {
            throw new AppException("Dispatch SLA has expired; the fulfillment requires admin review", HttpStatus.CONFLICT);
        }
        fulfillment.setStatus("PACKED");
        order.setUpdatedAt(LocalDateTime.now());
        appendHistory(order, "SELLER_FULFILLMENT_PACKED", sellerId, "Seller marked fulfillment packed; carrier handover is still pending");
        return orders.save(order);
    }

    @Override
    @Transactional
    public Order bookSellerFulfillmentShipment(String sellerId, String orderId, String fulfillmentId) {
        Order order = orders.findByIdAndSellerFulfillmentsSellerId(orderId, sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        Order.SellerFulfillment fulfillment = order.getSellerFulfillments().stream()
                .filter(value -> Objects.equals(value.getId(), fulfillmentId)
                        && Objects.equals(value.getSellerId(), sellerId))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Seller fulfillment", "id", fulfillmentId));

        if (!List.of("ACCEPTED", "PACKED").contains(fulfillment.getStatus())) {
            throw new AppException("Only an accepted or packed fulfillment can be booked for shipment", HttpStatus.CONFLICT);
        }

        if (fulfillment.getDispatchSlaDeadlineAt() != null
                && LocalDateTime.now().isAfter(fulfillment.getDispatchSlaDeadlineAt())) {
            throw new AppException("Dispatch SLA has expired; the fulfillment requires admin review", HttpStatus.CONFLICT);
        }

        if ("ACCEPTED".equals(fulfillment.getStatus())) {
            fulfillment.setStatus("PACKED");
        }

        com.ebs.biocrop.dto.response.ShiprocketOrderResult result = null;
        if (shiprocketService != null) {
            result = shiprocketService.createFulfillmentShipment(order, fulfillmentId);
        }
        if (result == null) {
            long mockSrId = 10000000L + Math.abs((order.getOrderNumber() + fulfillmentId).hashCode() % 8999999L);
            String mockAwb = "SRK" + (mockSrId % 100000000L) + "IN";
            result = new com.ebs.biocrop.dto.response.ShiprocketOrderResult(
                    mockSrId, mockSrId + 1, mockAwb, "Delhivery Surface (Shiprocket)", "10",
                    "https://shiprocket.co/tracking/" + mockAwb, "READY_FOR_PICKUP");
        }

        LocalDateTime now = LocalDateTime.now();
        Order.Shipment shipment = new Order.Shipment();
        shipment.setShipmentId(String.valueOf(result.shipmentId()));
        shipment.setItemOrderIds(new ArrayList<>(fulfillment.getItemOrderIds()));
        shipment.setStatus("READY_FOR_PICKUP");
        shipment.setCarrier(result.courierName());
        shipment.setTrackingNumber(result.awbCode());
        shipment.setLabelReference(result.trackingUrl());
        shipment.setLabelCreatedAt(now);
        shipment.setLastTrackingUpdateAt(now);
        shipment.setLastTrackingDescription("Shipment booked via " + result.courierName() + "; AWB: " + result.awbCode());

        if (fulfillment.getShipments() == null) fulfillment.setShipments(new ArrayList<>());
        fulfillment.getShipments().add(shipment);

        order.setUpdatedAt(now);
        appendHistory(order, "SHIPMENT_BOOKED", sellerId,
                "Shipment booked for fulfillment " + fulfillmentId + " via " + result.courierName() + " (AWB: " + result.awbCode() + ")");

        return orders.save(order);
    }

    @Override
    @Transactional
    public Order recordCarrierHandover(String orderId, String fulfillmentId, String trackingNumber, LocalDateTime handoverTime) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));

        Order.SellerFulfillment fulfillment = order.getSellerFulfillments().stream()
                .filter(value -> Objects.equals(value.getId(), fulfillmentId))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Seller fulfillment", "id", fulfillmentId));

        if (!List.of("PACKED", "ACCEPTED", "IN_DELAY_REVIEW").contains(fulfillment.getStatus())) {
            if ("DISPATCHED".equals(fulfillment.getStatus()) || "DELIVERED".equals(fulfillment.getStatus())) {
                return order; // idempotent repeat-safe
            }
            throw new AppException("Fulfillment in status [" + fulfillment.getStatus() + "] cannot be marked dispatched", HttpStatus.CONFLICT);
        }

        LocalDateTime eventTime = handoverTime != null ? handoverTime : LocalDateTime.now();
        fulfillment.setStatus("DISPATCHED");
        fulfillment.setDispatchedAt(eventTime);

        // Update matching shipment
        if (fulfillment.getShipments() != null) {
            for (Order.Shipment shipment : fulfillment.getShipments()) {
                if (trackingNumber == null || trackingNumber.isBlank() || Objects.equals(shipment.getTrackingNumber(), trackingNumber)) {
                    shipment.setStatus("IN_TRANSIT");
                    shipment.setPickupConfirmedAt(eventTime);
                    shipment.setLastTrackingUpdateAt(eventTime);
                    shipment.setLastTrackingDescription("Carrier handover confirmed; parcel in transit");
                }
            }
        }

        // Transition items in this fulfillment group to DISPATCHED
        List<String> affectedItems = fulfillment.getItemOrderIds() == null ? List.of() : fulfillment.getItemOrderIds();
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                if (affectedItems.contains(item.getItemOrderId()) && !"CANCELLED_BY_CUSTOMER".equals(item.getItemStatus())
                        && !"CANCELLED_BY_SELLER".equals(item.getItemStatus())) {
                    item.setItemStatus("DISPATCHED");
                }
            }
        }

        // If all active fulfillments in this unified order are now DISPATCHED (or DELIVERED), advance overallStatus
        boolean allActiveDispatched = order.getSellerFulfillments().stream()
                .filter(f -> !"CANCELLED".equals(f.getStatus()) && !"REJECTED".equals(f.getStatus()) && !"EXPIRED_TIMEOUT".equals(f.getStatus()))
                .allMatch(f -> "DISPATCHED".equals(f.getStatus()) || "DELIVERED".equals(f.getStatus()));

        if (allActiveDispatched) {
            order.setOverallStatus("DISPATCHED");
        }

        order.setUpdatedAt(eventTime);
        appendHistory(order, "CARRIER_HANDOVER_CONFIRMED", "CARRIER",
                "Carrier handover confirmed for fulfillment " + fulfillmentId + (trackingNumber != null ? " (AWB: " + trackingNumber + ")" : ""));

        return orders.save(order);
    }

    @Override
    @Transactional
    public Order resolveCustomerDecisionWindow(String customerId, String orderId, String decisionWindowId,
                                              String decision, List<String> itemOrderIds) {
        Order order = orders.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        Order.CustomerDecisionWindow window = (order.getCustomerDecisionWindows() == null ? List.<Order.CustomerDecisionWindow>of() : order.getCustomerDecisionWindows()).stream()
                .filter(value -> Objects.equals(value.getDecisionWindowId(), decisionWindowId))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Customer decision window", "id", decisionWindowId));
        if (!"OPEN".equals(window.getStatus()) || window.getDeadlineAt() == null
                || LocalDateTime.now().isAfter(window.getDeadlineAt())) {
            throw new AppException("Customer decision window is closed", HttpStatus.CONFLICT);
        }
        String choice = decision == null ? "" : decision.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("CONTINUE", "CANCEL_ORDER", "CANCEL_ITEMS").contains(choice)) {
            throw new AppException("Decision must be CONTINUE, CANCEL_ORDER, or CANCEL_ITEMS", HttpStatus.BAD_REQUEST);
        }
        List<OrderItem> items = order.getItems() == null ? new ArrayList<>() : order.getItems();
        List<OrderItem> toCancel;
        if ("CANCEL_ORDER".equals(choice)) {
            toCancel = items.stream().filter(this::isStillActive).toList();
        } else if ("CANCEL_ITEMS".equals(choice)) {
            if (itemOrderIds == null || itemOrderIds.isEmpty()) {
                throw new AppException("itemOrderIds are required for CANCEL_ITEMS", HttpStatus.BAD_REQUEST);
            }
            toCancel = items.stream().filter(item -> itemOrderIds.contains(item.getItemOrderId()) && isStillActive(item)).toList();
            if (toCancel.size() != new java.util.HashSet<>(itemOrderIds).size()) {
                throw new AppException("One or more requested items are not cancellable in this order", HttpStatus.CONFLICT);
            }
        } else {
            toCancel = List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        if (!toCancel.isEmpty()) {
            releaseStock(toCancel);
            toCancel.forEach(item -> {
                item.setItemStatus("CANCELLED_BY_CUSTOMER");
                markCancelled(item, customerId, "CUSTOMER_DECISION_WINDOW_" + choice);
            });
            BigDecimal cancelledAdvance = toCancel.stream().map(OrderItem::getAdvanceAllocatedAtCheckout).reduce(BigDecimal.ZERO, BigDecimal::add);
            Order.Refund refund = new Order.Refund();
            refund.setRefundId(java.util.UUID.randomUUID().toString());
            refund.setItemOrderIds(toCancel.stream().map(OrderItem::getItemOrderId).toList());
            refund.setAmount("CANCEL_ORDER".equals(choice)
                    ? order.getPayment().getAmountPaid().subtract(pendingOrCompletedRefunds(order))
                    : cancelledAdvance);
            refund.setReason("CUSTOMER_" + choice);
            refund.setStatus("PENDING_PROVIDER_INTEGRATION");
            refund.setOccurredAt(now);
            if (refund.getAmount().signum() > 0) {
                if (order.getPayment().getRefunds() == null) order.getPayment().setRefunds(new ArrayList<>());
                order.getPayment().getRefunds().add(refund);
            }
            applyCancelledAmounts(order, toCancel);
            order.getFinancialSummary().setNetOnlineAmountRetained(order.getPayment().getAmountPaid().subtract(pendingOrCompletedRefunds(order)).max(BigDecimal.ZERO));
            if (pendingOrCompletedRefunds(order).signum() > 0) order.getPayment().setStatus("REFUND_PENDING");
        }

        window.setStatus("RESOLVED");
        window.setResolution(choice);
        window.setResolvedAt(now);
        boolean anyOpenWindows = (order.getCustomerDecisionWindows() == null ? List.<Order.CustomerDecisionWindow>of() : order.getCustomerDecisionWindows()).stream().anyMatch(value -> "OPEN".equals(value.getStatus()));
        boolean anyActive = items.stream().anyMatch(this::isStillActive);
        for (Order.SellerFulfillment fulfillment : order.getSellerFulfillments()) {
            boolean fulfillmentHasActiveItems = items.stream().anyMatch(item -> Objects.equals(item.getSellerFulfillmentId(), fulfillment.getId()) && isStillActive(item));
            if (!fulfillmentHasActiveItems && !"REJECTED".equals(fulfillment.getStatus())
                    && !"EXPIRED_TIMEOUT".equals(fulfillment.getStatus())) fulfillment.setStatus("CANCELLED");
        }
        if (!anyOpenWindows) {
            boolean awaitingSeller = order.getSellerFulfillments().stream()
                    .anyMatch(fulfillment -> "AWAITING_SELLER_RESPONSE".equals(fulfillment.getStatus()));
            order.setOverallStatus(anyActive ? (awaitingSeller ? "AWAITING_SELLER_RESPONSE" : "PROCESSING") : "CANCELLED");
        }
        if (!anyActive) {
            order.setStockReserved(false);
            order.setCancelledAt(now);
        }
        order.setUpdatedAt(now);
        appendHistory(order, "CUSTOMER_DECISION_" + choice, customerId, "Customer resolved seller fulfillment decision window");
        return orders.save(order);
    }

    private boolean isStillActive(OrderItem item) {
        return item != null && !List.of("CANCELLED_BY_CUSTOMER", "CANCELLED_BY_SELLER", "CANCELLED_BY_SYSTEM", "DELIVERED", "RETURNED")
                .contains(item.getItemStatus());
    }

    private BigDecimal pendingOrCompletedRefunds(Order order) {
        if (order.getPayment().getRefunds() == null) return BigDecimal.ZERO;
        return order.getPayment().getRefunds().stream()
                .filter(refund -> List.of("PENDING_PROVIDER_INTEGRATION", "PROCESSED").contains(refund.getStatus()))
                .map(Order.Refund::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void applyCancelledAmounts(Order order, List<OrderItem> items) {
        BigDecimal cancelledTotal = items.stream().map(OrderItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cancelledCod = items.stream().map(OrderItem::getCodDueAtCheckout).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cancelledCodCharges = items.stream().map(item -> item.getCodDueAtCheckout()
                .subtract(item.getLineTotal().subtract(item.getAdvanceAllocatedAtCheckout())).max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.getFinancialSummary().setCancelledAmount(order.getFinancialSummary().getCancelledAmount().add(cancelledTotal));
        order.getFinancialSummary().setCurrentOrderTotal(order.getFinancialSummary().getCurrentOrderTotal()
                .subtract(cancelledTotal).subtract(cancelledCodCharges).max(BigDecimal.ZERO));
        order.getFinancialSummary().setCodDue(order.getFinancialSummary().getCodDue().subtract(cancelledCod).max(BigDecimal.ZERO));
        order.getPayment().setCodDue(order.getPayment().getCodDue().subtract(cancelledCod).max(BigDecimal.ZERO));
        order.getPayment().setRemainingAmount(order.getPayment().getCodDue());
    }

    private void markCancelled(OrderItem item, String actor, String reason) {
        LocalDateTime now = LocalDateTime.now();
        Order.ItemCancellation cancellation = new Order.ItemCancellation();
        cancellation.setStatus("CONFIRMED");
        cancellation.setCancelledBy(actor);
        cancellation.setReason(reason);
        cancellation.setRequestedAt(now);
        cancellation.setCancelledAt(now);
        item.setCancellation(cancellation);
    }

    private void releaseStock(List<OrderItem> items) {
        for (OrderItem item : items) {
            Query query = Query.query(Criteria.where("_id").is(item.getProductId()).and("variants._id").is(item.getVariantId()));
            Product updated = mongoTemplate.findAndModify(query,
                    new Update().inc("variants.$.stock", item.getQuantity()).inc("version", 1),
                    FindAndModifyOptions.options().returnNew(true), Product.class);
            if (updated == null) throw new AppException("Could not safely release reserved stock for item " + item.getItemOrderId(), HttpStatus.CONFLICT);
        }
        if (!items.isEmpty()) redisCache.invalidateRegionAfterCommit("public-products");
    }

    @Scheduled(fixedDelayString = "${app.orders.lifecycle-sweep-interval-ms:60000}")
    @Transactional
    public void processExpiredSellerResponseWindows() {
        LocalDateTime now = LocalDateTime.now();
        Query query = Query.query(Criteria.where("sellerFulfillments").elemMatch(
                Criteria.where("status").is("AWAITING_SELLER_RESPONSE").and("responseDeadlineAt").lte(now)))
                .limit(100);
        for (Order order : mongoTemplate.find(query, Order.class)) {
            boolean changed = false;
            for (Order.SellerFulfillment fulfillment : order.getSellerFulfillments()) {
                if (!"AWAITING_SELLER_RESPONSE".equals(fulfillment.getStatus())
                        || fulfillment.getResponseDeadlineAt() == null || fulfillment.getResponseDeadlineAt().isAfter(now)) continue;
                List<String> itemIds = fulfillment.getItemOrderIds() == null ? List.of() : fulfillment.getItemOrderIds();
                List<OrderItem> expiredItems = order.getItems().stream().filter(item -> itemIds.contains(item.getItemOrderId())).toList();
                releaseStock(expiredItems);
                expiredItems.forEach(item -> {
                    item.setItemStatus("CANCELLED_BY_SYSTEM");
                    markCancelled(item, "SYSTEM", "SELLER_RESPONSE_TIMEOUT");
                });
                applyCancelledAmounts(order, expiredItems);
                fulfillment.setStatus("EXPIRED_TIMEOUT");
                Order.Refund refund = new Order.Refund();
                refund.setRefundId(java.util.UUID.randomUUID().toString());
                refund.setItemOrderIds(new ArrayList<>(itemIds));
                refund.setAmount(expiredItems.stream().map(OrderItem::getAdvanceAllocatedAtCheckout).reduce(BigDecimal.ZERO, BigDecimal::add));
                refund.setReason("SELLER_RESPONSE_TIMEOUT");
                refund.setStatus("PENDING_PROVIDER_INTEGRATION");
                refund.setOccurredAt(now);
                if (refund.getAmount().signum() > 0) {
                    if (order.getPayment().getRefunds() == null) order.getPayment().setRefunds(new ArrayList<>());
                    order.getPayment().getRefunds().add(refund);
                    order.getPayment().setStatus("REFUND_PENDING");
                    order.getFinancialSummary().setNetOnlineAmountRetained(
                            order.getPayment().getAmountPaid().subtract(pendingOrCompletedRefunds(order)).max(BigDecimal.ZERO));
                }
                Order.CustomerDecisionWindow window = new Order.CustomerDecisionWindow();
                window.setDecisionWindowId(java.util.UUID.randomUUID().toString());
                window.setTriggerId("SELLER_TIMEOUT");
                window.setSellerFulfillmentId(fulfillment.getId());
                window.setAffectedItemOrderIds(new ArrayList<>(itemIds));
                window.setOpenedAt(now);
                window.setDeadlineAt(now.plusHours(6));
                window.setStatus("OPEN");
                order.getCustomerDecisionWindows().add(window);
                appendHistory(order, "SELLER_RESPONSE_EXPIRED", "SYSTEM", "Seller response window expired; affected items cancelled and refund recorded as pending provider support");
                changed = true;
            }
            if (changed) {
                order.setOverallStatus("CUSTOMER_DECISION_REQUIRED");
                order.setUpdatedAt(now);
                orders.save(order);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.orders.lifecycle-sweep-interval-ms:60000}")
    @Transactional
    public void continueOrdersAfterExpiredCustomerDecisionWindows() {
        LocalDateTime now = LocalDateTime.now();
        Query query = Query.query(Criteria.where("customerDecisionWindows").elemMatch(
                Criteria.where("status").is("OPEN").and("deadlineAt").lte(now))).limit(100);
        for (Order order : mongoTemplate.find(query, Order.class)) {
            boolean changed = false;
            for (Order.CustomerDecisionWindow window : order.getCustomerDecisionWindows()) {
                if (!"OPEN".equals(window.getStatus()) || window.getDeadlineAt() == null || window.getDeadlineAt().isAfter(now)) continue;
                window.setStatus("RESOLVED");
                window.setResolution("CONTINUE");
                window.setResolvedAt(now);
                appendHistory(order, "CUSTOMER_DECISION_DEFAULTED_CONTINUE", "SYSTEM", "Decision window expired; remaining seller fulfillments continue");
                changed = true;
            }
            if (changed) {
                boolean hasOpen = order.getCustomerDecisionWindows().stream().anyMatch(window -> "OPEN".equals(window.getStatus()));
                if (!hasOpen) {
                    boolean anyActive = order.getItems().stream().anyMatch(this::isStillActive);
                    boolean awaitingSeller = order.getSellerFulfillments().stream()
                            .anyMatch(fulfillment -> "AWAITING_SELLER_RESPONSE".equals(fulfillment.getStatus()));
                    order.setOverallStatus(anyActive ? (awaitingSeller ? "AWAITING_SELLER_RESPONSE" : "PROCESSING") : "CANCELLED");
                }
                order.setUpdatedAt(now);
                orders.save(order);
            }
        }
    }

    /** Flags accepted/packed groups for admin review when the dispatch deadline passes without carrier handover. */
    @Scheduled(fixedDelayString = "${app.orders.lifecycle-sweep-interval-ms:60000}")
    @Transactional
    public void flagExpiredDispatchSlaForAdminReview() {
        LocalDateTime now = LocalDateTime.now();
        Query query = Query.query(Criteria.where("sellerFulfillments").elemMatch(
                Criteria.where("status").in("ACCEPTED", "PACKED").and("dispatchSlaDeadlineAt").lte(now)))
                .limit(100);
        for (Order order : mongoTemplate.find(query, Order.class)) {
            boolean changed = false;
            for (Order.SellerFulfillment fulfillment : order.getSellerFulfillments()) {
                if (!List.of("ACCEPTED", "PACKED").contains(fulfillment.getStatus())
                        || fulfillment.getDispatchSlaDeadlineAt() == null
                        || fulfillment.getDispatchSlaDeadlineAt().isAfter(now)) continue;
                fulfillment.setStatus("IN_DELAY_REVIEW");
                Order.DelayReview review = fulfillment.getDelayReview();
                if (review == null) {
                    review = new Order.DelayReview();
                    fulfillment.setDelayReview(review);
                }
                review.setStatus("OPEN");
                review.setReason("DISPATCH_SLA_EXPIRED_WITHOUT_CONFIRMED_CARRIER_HANDOVER");
                review.setOpenedBy("SYSTEM");
                review.setOpenedAt(now);
                appendHistory(order, "DISPATCH_SLA_EXPIRED", "SYSTEM", "Admin review required; no carrier handover was confirmed within 48 hours");
                changed = true;
            }
            if (changed) {
                order.setUpdatedAt(now);
                orders.save(order);
            }
        }
    }

    private void appendHistory(Order order, String status, String actor, String note) {
        List<OrderStatusEvent> history = order.getStatusHistory() == null
                ? new ArrayList<>() : new ArrayList<>(order.getStatusHistory());
        OrderStatusEvent event = new OrderStatusEvent();
        event.setStatus(status);
        event.setOccurredAt(LocalDateTime.now());
        event.setActor(actor);
        event.setNote(note);
        history.add(event);
        order.setStatusHistory(history);
    }

    private Order cancel(Order order, String actor) {
        boolean unpaidCod = order.getPayment() != null && "COD".equalsIgnoreCase(order.getPayment().getPaymentType())
                && "AWAITING_COD_COLLECTION".equals(order.getPayment().getStatus())
                && "AWAITING_SELLER_RESPONSE".equals(order.getOverallStatus());
        if (!"AWAITING_ADVANCE_PAYMENT".equals(order.getOverallStatus()) && !unpaidCod) {
            throw new AppException("This order can only be cancelled before payment or seller processing begins", HttpStatus.CONFLICT);
        }
        if (order.getPayment() != null && order.getPayment().getAmountPaid().signum() > 0) {
            throw new AppException("An order with captured money cannot be cancelled until refund processing is available", HttpStatus.CONFLICT);
        }
        boolean stockWasReserved = Boolean.TRUE.equals(order.getStockReserved());
        if (stockWasReserved && order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                if (item.getProductId() == null || item.getVariantId() == null || item.getQuantity() == null
                        || item.getQuantity() <= 0) {
                    throw new AppException("Reserved order item is incomplete; stock cannot be safely released", HttpStatus.CONFLICT);
                }
                Query variantQuery = Query.query(Criteria.where("_id").is(item.getProductId())
                        .and("variants._id").is(item.getVariantId()));
                Product updatedProduct = mongoTemplate.findAndModify(variantQuery,
                        new Update().inc("variants.$.stock", item.getQuantity()),
                        FindAndModifyOptions.options().returnNew(true), Product.class);
                if (updatedProduct == null) {
                    throw new AppException("Could not safely release reserved stock for order item "
                            + item.getItemOrderId(), HttpStatus.CONFLICT);
                }
                ProductVariant updatedVariant = updatedProduct.getVariants().stream()
                        .filter(variant -> item.getVariantId().equals(variant.getId()))
                        .findFirst()
                        .orElseThrow(() -> new AppException("Could not verify released stock for order item "
                                + item.getItemOrderId(), HttpStatus.CONFLICT));
                webSocketService.broadcast("inventory:stock_update", Map.of(
                        "productId", item.getProductId(), "variantId", item.getVariantId(),
                        "stock", updatedVariant.getStock() == null ? 0 : updatedVariant.getStock()));
            }
            order.setStockReserved(false);
        }
        LocalDateTime now = LocalDateTime.now();
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                item.setItemStatus("CANCELLED_BY_CUSTOMER");
                Order.ItemCancellation cancellation = new Order.ItemCancellation();
                cancellation.setStatus("CONFIRMED");
                cancellation.setCancelledBy(actor);
                cancellation.setReason("ORDER_CANCELLED_BEFORE_ADVANCE_PAYMENT");
                cancellation.setRequestedAt(now);
                cancellation.setCancelledAt(now);
                item.setCancellation(cancellation);
            }
        }
        if (order.getSellerFulfillments() != null) {
            order.getSellerFulfillments().forEach(group -> group.setStatus("CANCELLED"));
        }
        order.getFinancialSummary().setCancelledAmount(order.getFinancialSummary().getOriginalOrderTotal());
        order.getFinancialSummary().setCurrentOrderTotal(java.math.BigDecimal.ZERO);
        order.getFinancialSummary().setAdvanceAmountDue(java.math.BigDecimal.ZERO);
        order.getFinancialSummary().setCodDue(java.math.BigDecimal.ZERO);
        order.getPayment().setStatus("CANCELLED_UNPAID");
        order.getPayment().setAdvanceAmountDue(java.math.BigDecimal.ZERO);
        order.getPayment().setAdvanceAmountPaid(java.math.BigDecimal.ZERO);
        order.getPayment().setRemainingAmount(java.math.BigDecimal.ZERO);
        order.getPayment().setRemainingPercentage(java.math.BigDecimal.ZERO);
        order.getPayment().setCodDue(java.math.BigDecimal.ZERO);
        order.setOverallStatus("CANCELLED");
        order.setCancelledAt(now);
        order.setUpdatedAt(now);
        List<OrderStatusEvent> history = order.getStatusHistory() == null
                ? new ArrayList<>() : new ArrayList<>(order.getStatusHistory());
        OrderStatusEvent event = new OrderStatusEvent();
        event.setStatus("ORDER_CANCELLED");
        event.setOccurredAt(now);
        event.setActor(actor);
        event.setNote(stockWasReserved
                ? "Order cancelled before advance payment; reserved stock released"
                : "Order cancelled before advance payment; no refund or stock release was needed");
        history.add(event);
        order.setStatusHistory(history);
        Order saved = orders.save(order);
        redisCache.invalidateRegionAfterCommit("public-products");
        webSocketService.broadcast("orders:status_change", Map.of(
                "orderId", saved.getId(), "oldStatus", "AWAITING_ADVANCE_PAYMENT", "newStatus", "CANCELLED"));
        return saved;
    }
}
