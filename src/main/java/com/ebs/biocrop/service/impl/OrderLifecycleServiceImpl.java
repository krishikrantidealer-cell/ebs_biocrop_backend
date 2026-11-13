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
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

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
    public Order bookSellerFulfillmentShipment(String sellerId, String orderId, String fulfillmentId) {
        return bookSellerFulfillmentShipment(sellerId, orderId, fulfillmentId, null);
    }

    @Override
    public Order bookSellerFulfillmentShipment(String sellerId, String orderId, String fulfillmentId,
                                               List<String> requestedItemOrderIds) {
        return bookSellerFulfillmentShipment(sellerId, orderId, fulfillmentId, requestedItemOrderIds, null);
    }

    @Override
    public Order bookSellerFulfillmentShipment(String sellerId, String orderId, String fulfillmentId,
                                               List<String> requestedItemOrderIds, Order.Shipment packageDetails) {
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

        Set<String> assignedItemIds = fulfillment.getShipments() == null ? new HashSet<>()
                : fulfillment.getShipments().stream()
                .filter(Objects::nonNull)
                .filter(shipment -> shipment.getItemOrderIds() != null)
                .flatMap(shipment -> shipment.getItemOrderIds().stream())
                .collect(Collectors.toSet());
        Set<String> activeItemIds = (order.getItems() == null ? List.<OrderItem>of() : order.getItems()).stream()
                .filter(item -> Objects.equals(item.getSellerFulfillmentId(), fulfillmentId))
                .filter(item -> !String.valueOf(item.getItemStatus()).startsWith("CANCELLED"))
                .map(OrderItem::getItemOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> packageItemIds = requestedItemOrderIds == null
                ? activeItemIds.stream().filter(id -> !assignedItemIds.contains(id)).toList()
                : new ArrayList<>(requestedItemOrderIds);
        if (packageItemIds.isEmpty()) {
            throw new AppException("No unassigned active items remain for this fulfillment", HttpStatus.CONFLICT);
        }
        if (new HashSet<>(packageItemIds).size() != packageItemIds.size()
                || !activeItemIds.containsAll(packageItemIds)
                || packageItemIds.stream().anyMatch(assignedItemIds::contains)) {
            throw new AppException("Shipment items must be unique, active, owned by this fulfillment, and not already assigned",
                    HttpStatus.BAD_REQUEST);
        }
        if (packageDetails == null || !isPositive(packageDetails.getPackageWeightKg())
                || !isPositive(packageDetails.getLengthCm()) || !isPositive(packageDetails.getBreadthCm())
                || !isPositive(packageDetails.getHeightCm())) {
            throw new AppException("Package weight and all three package dimensions are required and must be positive",
                    HttpStatus.BAD_REQUEST);
        }
        if (shiprocketService == null) {
            throw new AppException("Shiprocket shipment booking is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }
        LocalDateTime now = LocalDateTime.now();
        Order.Shipment shipment = new Order.Shipment();
        String bookingReference = shipmentBookingReference(order, fulfillmentId, packageItemIds);
        shipment.setShipmentId("PENDING-" + bookingReference);
        shipment.setBookingReference(bookingReference);
        shipment.setItemOrderIds(new ArrayList<>(packageItemIds));
        shipment.setPackageWeightKg(packageDetails.getPackageWeightKg());
        shipment.setLengthCm(packageDetails.getLengthCm());
        shipment.setBreadthCm(packageDetails.getBreadthCm());
        shipment.setHeightCm(packageDetails.getHeightCm());
        shipment.setStatus("BOOKING_PENDING");
        shipment.setLastTrackingUpdateAt(now);
        shipment.setLastTrackingDescription("Shiprocket booking request started; reference " + bookingReference);

        if (fulfillment.getShipments() == null) fulfillment.setShipments(new ArrayList<>());
        fulfillment.getShipments().add(shipment);
        Set<String> allAssignedItemIds = new HashSet<>(assignedItemIds);
        allAssignedItemIds.addAll(packageItemIds);
        if (allAssignedItemIds.containsAll(activeItemIds)) {
            fulfillment.setStatus("PACKED");
        }

        order.setUpdatedAt(now);
        appendHistory(order, "SHIPMENT_BOOKING_STARTED", sellerId,
                "Shiprocket booking started for fulfillment " + fulfillmentId + " (reference: " + bookingReference + ")");

        // Commit the item reservation before the external call. @Version makes concurrent requests race on
        // this save, so only one can reach Shiprocket for a given item set.
        Order savedOrder = orders.save(order);
        if (savedOrder != null) order = savedOrder;
        Order.SellerFulfillment savedFulfillment = order.getSellerFulfillments().stream()
                .filter(value -> Objects.equals(value.getId(), fulfillmentId))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Seller fulfillment", "id", fulfillmentId));
        Order.Shipment pendingShipment = savedFulfillment.getShipments().stream()
                .filter(value -> Objects.equals(value.getBookingReference(), bookingReference))
                .findFirst().orElseThrow(() -> new AppException("Shipment booking reservation was not persisted", HttpStatus.CONFLICT));
        packageDetails.setBookingReference(bookingReference);
        try {
            com.ebs.biocrop.dto.response.ShiprocketOrderResult result =
                    shiprocketService.createFulfillmentShipment(order, fulfillmentId, packageItemIds, packageDetails);
            if (result == null || result.shipmentId() <= 0 || result.awbCode() == null || result.awbCode().isBlank()) {
                throw new AppException("Shiprocket did not return a valid shipment ID and AWB", HttpStatus.BAD_GATEWAY);
            }
            pendingShipment.setShipmentId(String.valueOf(result.shipmentId()));
            pendingShipment.setStatus("READY_FOR_PICKUP");
            pendingShipment.setCarrier(result.courierName());
            pendingShipment.setTrackingNumber(result.awbCode());
            pendingShipment.setLabelReference(result.trackingUrl());
            pendingShipment.setLabelCreatedAt(LocalDateTime.now());
            pendingShipment.setLastTrackingUpdateAt(LocalDateTime.now());
            pendingShipment.setLastTrackingDescription("Shipment booked via " + result.courierName() + "; AWB: " + result.awbCode());
            order.setUpdatedAt(LocalDateTime.now());
            appendHistory(order, "SHIPMENT_BOOKED", sellerId,
                    "Shipment booked for fulfillment " + fulfillmentId + " via " + result.courierName() + " (AWB: " + result.awbCode() + ")");
            Order completed = orders.save(order);
            return completed == null ? order : completed;
        } catch (RuntimeException ex) {
            pendingShipment.setStatus("BOOKING_REQUIRES_RECONCILIATION");
            pendingShipment.setLastTrackingUpdateAt(LocalDateTime.now());
            pendingShipment.setLastTrackingDescription("Shiprocket booking result is uncertain; reconcile reference " + bookingReference
                    + " before attempting another booking");
            order.setUpdatedAt(LocalDateTime.now());
            appendHistory(order, "SHIPMENT_BOOKING_REQUIRES_RECONCILIATION", "SYSTEM",
                    "Shiprocket booking outcome requires reconciliation for reference " + bookingReference);
            orders.save(order);
            if (ex instanceof AppException appException) throw appException;
            throw new AppException("Shiprocket booking outcome is uncertain; reconcile the booking before retrying", HttpStatus.BAD_GATEWAY);
        }
    }

    private String shipmentBookingReference(Order order, String fulfillmentId, List<String> itemOrderIds) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            String canonical = order.getId() + "|" + fulfillmentId + "|" + itemOrderIds.stream().sorted().collect(Collectors.joining(","));
            String suffix = java.util.HexFormat.of().formatHex(digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 6);
            return order.getOrderNumber() + "-" + suffix;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private boolean isPositive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }

    @Override
    @Transactional
    public Order recordCarrierHandover(String orderId, String fulfillmentId, String trackingNumber, LocalDateTime handoverTime) {
        return recordCarrierTrackingStatus(orderId, fulfillmentId, trackingNumber, 42, "PICKED UP", handoverTime);
    }

    @Override
    @Transactional
    public Order recordCarrierTrackingStatus(String orderId, String fulfillmentId, String trackingNumber,
                                             Integer statusId, String carrierStatus, LocalDateTime eventTime) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));

        Order.SellerFulfillment fulfillment = order.getSellerFulfillments().stream()
                .filter(value -> Objects.equals(value.getId(), fulfillmentId))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Seller fulfillment", "id", fulfillmentId));
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new AppException("Carrier tracking number is required to confirm a shipment handover", HttpStatus.BAD_REQUEST);
        }
        Order.Shipment shipment = (fulfillment.getShipments() == null ? List.<Order.Shipment>of() : fulfillment.getShipments()).stream()
                .filter(value -> Objects.equals(value.getTrackingNumber(), trackingNumber))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Shipment", "trackingNumber", trackingNumber));
        String nextStatus = mapShiprocketStatus(statusId, carrierStatus);
        LocalDateTime appliedAt = eventTime == null ? LocalDateTime.now() : eventTime;
        if (nextStatus == null) {
            // Unknown scans are retained for support visibility but cannot advance order state.
            if (shipment.getLastTrackingUpdateAt() != null && !appliedAt.isAfter(shipment.getLastTrackingUpdateAt())) return order;
            shipment.setLastTrackingUpdateAt(appliedAt);
            shipment.setLastTrackingDescription(carrierStatus == null ? "Unmapped Shiprocket status ID: " + statusId : carrierStatus);
            order.setUpdatedAt(appliedAt);
            return orders.save(order);
        }
        int currentRank = shipmentStatusRank(shipment.getStatus());
        int nextRank = shipmentStatusRank(nextStatus);
        if ((nextRank > 0 && currentRank > nextRank)
                || (currentRank >= 4 && nextRank == 0)
                || (Objects.equals(shipment.getStatus(), nextStatus))
                || (shipment.getLastTrackingUpdateAt() != null
                && !appliedAt.isAfter(shipment.getLastTrackingUpdateAt()))) {
            return order; // Duplicate or out-of-order carrier callbacks are safe no-ops.
        }

        String previousStatus = shipment.getStatus();
        shipment.setStatus(nextStatus);
        shipment.setLastTrackingUpdateAt(appliedAt);
        shipment.setLastTrackingDescription(carrierStatus == null ? nextStatus : carrierStatus);
        if ("IN_TRANSIT".equals(nextStatus) && shipment.getPickupConfirmedAt() == null) {
            shipment.setPickupConfirmedAt(appliedAt);
        }

        List<String> affectedItems = shipment.getItemOrderIds() == null ? List.of() : shipment.getItemOrderIds();
        if (order.getItems() != null) {
            String itemStatus = "DELIVERED".equals(nextStatus) ? "DELIVERED"
                    : "OUT_FOR_DELIVERY".equals(nextStatus) ? "OUT_FOR_DELIVERY" : "DISPATCHED";
            for (OrderItem item : order.getItems()) {
                if (affectedItems.contains(item.getItemOrderId()) && !String.valueOf(item.getItemStatus()).startsWith("CANCELLED")) {
                    item.setItemStatus(itemStatus);
                }
            }
        }
        if ("DELIVERED".equals(nextStatus) && shipment.getDeliveredAt() == null) shipment.setDeliveredAt(appliedAt);

        List<OrderItem> allOrderItems = order.getItems() == null ? List.of() : order.getItems();
        List<OrderItem> fulfillmentItems = allOrderItems.stream()
                .filter(item -> Objects.equals(item.getSellerFulfillmentId(), fulfillmentId))
                .filter(item -> !String.valueOf(item.getItemStatus()).startsWith("CANCELLED"))
                .toList();
        boolean fulfillmentAllDelivered = !fulfillmentItems.isEmpty() && fulfillmentItems.stream().allMatch(i -> "DELIVERED".equals(i.getItemStatus()));
        boolean allDispatched = !fulfillmentItems.isEmpty() && fulfillmentItems.stream().allMatch(i ->
                List.of("DISPATCHED", "OUT_FOR_DELIVERY", "DELIVERED").contains(i.getItemStatus()));
        if (fulfillmentAllDelivered) {
            fulfillment.setStatus("DELIVERED");
        } else if (allDispatched) {
            fulfillment.setStatus("DISPATCHED");
            if (fulfillment.getDispatchedAt() == null) fulfillment.setDispatchedAt(appliedAt);
        }

        List<OrderItem> activeOrderItems = allOrderItems.stream()
                .filter(item -> !String.valueOf(item.getItemStatus()).startsWith("CANCELLED"))
                .toList();
        boolean anyShipped = activeOrderItems.stream().anyMatch(i -> List.of("DISPATCHED", "OUT_FOR_DELIVERY", "DELIVERED").contains(i.getItemStatus()));
        boolean allDelivered = !activeOrderItems.isEmpty() && activeOrderItems.stream().allMatch(i -> "DELIVERED".equals(i.getItemStatus()));
        boolean allShipped = !activeOrderItems.isEmpty() && activeOrderItems.stream().allMatch(i ->
                List.of("DISPATCHED", "OUT_FOR_DELIVERY", "DELIVERED").contains(i.getItemStatus()));
        if (allDelivered) {
            order.setOverallStatus("DELIVERED");
        } else if (allShipped) {
            order.setOverallStatus("DISPATCHED");
        } else if (anyShipped) {
            order.setOverallStatus("PARTIALLY_SHIPPED");
        }

        order.setUpdatedAt(appliedAt);
        appendHistory(order, "SHIPMENT_STATUS_UPDATED", "SHIPROCKET",
                "Shipment " + shipment.getShipmentId() + " status changed from " + previousStatus + " to " + nextStatus
                        + " (AWB: " + trackingNumber + ")");

        return orders.save(order);
    }

    private String mapShiprocketStatus(Integer statusId, String carrierStatus) {
        if (statusId != null) {
            return switch (statusId) {
                case 42, 51, 6, 18 -> "IN_TRANSIT";
                case 17 -> "OUT_FOR_DELIVERY";
                case 7, 26 -> "DELIVERED";
                case 8 -> "CANCELLED_BY_CARRIER";
                case 9 -> "RTO_INITIATED";
                case 10 -> "RTO_DELIVERED";
                case 12 -> "LOST";
                case 13, 20 -> "PICKUP_EXCEPTION";
                case 14 -> "RTO_ACKNOWLEDGED";
                case 15 -> "PICKUP_RESCHEDULED";
                case 16 -> "CANCELLATION_REQUESTED";
                case 19 -> "OUT_FOR_PICKUP";
                case 21 -> "UNDELIVERED";
                case 22 -> "DELAYED";
                case 23 -> "PARTIALLY_DELIVERED";
                case 24 -> "DESTROYED";
                case 25 -> "DAMAGED";
                case 27, 52 -> "PICKUP_BOOKED";
                case 38 -> "REACHED_DESTINATION_HUB";
                case 39 -> "MISROUTED";
                case 40 -> "RTO_NDR";
                case 41 -> "RTO_OUT_FOR_DELIVERY";
                case 43 -> "SELF_FULFILLED";
                case 44 -> "DISPOSED_OFF";
                case 45 -> "CANCELLED_BEFORE_DISPATCHED";
                case 46 -> "RTO_IN_TRANSIT";
                case 47 -> "QC_FAILED";
                case 48 -> "REACHED_WAREHOUSE";
                case 49 -> "CUSTOMS_CLEARED";
                case 50, 54 -> "IN_TRANSIT";
                case 55 -> "CONNECTION_ALIGNED";
                case 56 -> "REACHED_OVERSEAS_WAREHOUSE";
                case 57 -> "CUSTOMS_CLEARED_OVERSEAS";
                case 59 -> "BOX_PACKING";
                case 60 -> "FC_ALLOCATED";
                case 61 -> "PICKLIST_GENERATED";
                case 62 -> "READY_TO_PACK";
                case 63 -> "PACKED";
                case 67 -> "FC_MANIFEST_GENERATED";
                case 68 -> "PROCESSED_AT_WAREHOUSE";
                case 71 -> "HANDOVER_EXCEPTION";
                case 72 -> "PACKED_EXCEPTION";
                case 75 -> "RTO_LOCK";
                case 76 -> "UNTRACEABLE";
                case 77 -> "ISSUE_RELATED_TO_RECIPIENT";
                case 78 -> "REACHED_BACK_AT_SELLER_CITY";
                default -> null;
            };
        }
        if (carrierStatus == null) return null;
        return switch (carrierStatus.trim().toUpperCase(java.util.Locale.ROOT).replace('_', ' ')) {
            case "PICKED UP", "HANDOVER TO COURIER", "SHIPPED", "IN TRANSIT" -> "IN_TRANSIT";
            case "OUT FOR DELIVERY" -> "OUT_FOR_DELIVERY";
            case "DELIVERED" -> "DELIVERED";
            case "CANCELLED", "CANCELED" -> "CANCELLED_BY_CARRIER";
            case "RTO INITIATED", "RTO ACKNOWLEDGED", "RTO IN TRANSIT", "RTO NDR", "RTO OFD" -> "RETURN_TO_ORIGIN";
            case "RTO DELIVERED", "REACHED BACK AT SELLER CITY" -> "RTO_DELIVERED";
            case "LOST", "DESTROYED", "DAMAGED", "UNTRACEABLE" -> "DELIVERY_EXCEPTION";
            case "PICKUP ERROR", "PICKUP EXCEPTION", "HANDOVER EXCEPTION" -> "PICKUP_EXCEPTION";
            case "UNDELIVERED", "DELAYED", "MISROUTED", "ISSUE RELATED TO THE RECIPIENT" -> "DELIVERY_EXCEPTION";
            case "PARTIAL DELIVERED", "PARTIALLY DELIVERED" -> "PARTIALLY_DELIVERED";
            default -> null;
        };
    }

    private int shipmentStatusRank(String status) {
        if (status == null) return 0;
        return switch (status) {
            case "READY_FOR_PICKUP" -> 1;
            case "IN_TRANSIT" -> 2;
            case "OUT_FOR_DELIVERY" -> 3;
            case "DELIVERED" -> 4;
            default -> 0;
        };
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
