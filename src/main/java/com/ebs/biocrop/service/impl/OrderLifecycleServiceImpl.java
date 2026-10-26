package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.websocket.WebSocketService;
import java.util.Map;

import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.OrderItem;
import com.ebs.biocrop.entity.OrderStatusEvent;
import com.ebs.biocrop.entity.OrderStatus;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.service.OrderLifecycleService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.mongodb.client.result.UpdateResult;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderLifecycleServiceImpl implements OrderLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleServiceImpl.class);
    private final OrderRepository orders;
    private final MongoTemplate mongoTemplate;
    private final RedisJsonCache redisCache;
    private final WebSocketService webSocketService;

    public OrderLifecycleServiceImpl(OrderRepository orders, MongoTemplate mongoTemplate, RedisJsonCache redisCache,
                                    WebSocketService webSocketService) {
        this.orders = orders;
        this.mongoTemplate = mongoTemplate;
        this.redisCache = redisCache;
        this.webSocketService = webSocketService;
    }

    @Override
    @Transactional
    public Order cancelForCustomer(String customerId, String orderId) {
        Order order = orders.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        return cancel(order, customerId);
    }

    @Override
    @Transactional
    public Order cancelForAdmin(String adminId, String orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        return cancel(order, adminId);
    }

    @Override
    @Transactional
    public Order updateStatusForAdmin(String adminId, String orderId, OrderStatus requestedStatus, String note) {
        if (requestedStatus == null) {
            throw new AppException("Order status is required", HttpStatus.BAD_REQUEST);
        }
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        OrderStatus current = canonicalStatus(order.getOrderStatus());
        if (current == null) {
            throw new AppException("The current order status is not supported for transition", HttpStatus.CONFLICT);
        }
        if (requestedStatus == OrderStatus.CANCELED) {
            throw new AppException("Use the admin cancel endpoint so reserved stock and payment rules are enforced", HttpStatus.BAD_REQUEST);
        }
        OrderStatus transitionBase = current == OrderStatus.HOLD ? previousNonHoldStatus(order) : current;
        boolean resumingHeldStatus = current == OrderStatus.HOLD && transitionBase == requestedStatus;
        if (transitionBase == null || (!resumingHeldStatus && !isAllowedTransition(transitionBase, requestedStatus))) {
            throw new AppException("Order status transition is not allowed", HttpStatus.CONFLICT);
        }

        var now = LocalDateTime.now();
        order.setOrderStatus(requestedStatus.name());
        order.setUpdatedAt(now);
        List<OrderStatusEvent> history = order.getStatusHistory() == null
                ? new ArrayList<>() : new ArrayList<>(order.getStatusHistory());
        OrderStatusEvent event = new OrderStatusEvent();
        event.setStatus(requestedStatus.name());
        event.setChangedAt(now);
        event.setChangedBy(adminId);
        event.setNote(note == null || note.isBlank() ? null : note.trim());
        history.add(event);
        order.setStatusHistory(history);
        Order saved = orders.save(order);
        broadcastStatusChange(saved.getId(), current.name(), requestedStatus.name());
        return saved;
    }

    private boolean isAllowedTransition(OrderStatus current, OrderStatus next) {
        return switch (current) {
            case ORDERED -> next == OrderStatus.CONFIRMED || next == OrderStatus.HOLD;
            case CONFIRMED -> next == OrderStatus.PACKED || next == OrderStatus.HOLD;
            case PACKED -> next == OrderStatus.SHIPPED || next == OrderStatus.HOLD;
            case SHIPPED -> next == OrderStatus.DELIVERED || next == OrderStatus.RTO_SHIPPED || next == OrderStatus.HOLD;
            case RTO_SHIPPED -> next == OrderStatus.RTO_DELIVERED;
            case HOLD -> false;
            case DELIVERED, CANCELED, RTO_DELIVERED -> false;
        };
    }

    private OrderStatus previousNonHoldStatus(Order order) {
        List<OrderStatusEvent> history = order.getStatusHistory();
        if (history == null) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            OrderStatus status = canonicalStatus(history.get(i).getStatus());
            if (status != null && status != OrderStatus.HOLD && status != OrderStatus.CANCELED) return status;
        }
        return null;
    }

    private OrderStatus canonicalStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if ("PENDING".equals(normalized)) return OrderStatus.ORDERED;
        if ("CANCELLED".equals(normalized)) return OrderStatus.CANCELED;
        if ("RTO SHIPED".equals(normalized) || "RTO_SHIPED".equals(normalized)) return OrderStatus.RTO_SHIPPED;
        if ("RTO DELIVERED".equals(normalized)) return OrderStatus.RTO_DELIVERED;
        try {
            return OrderStatus.valueOf(normalized.replace(' ', '_'));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Order cancel(Order order, String actorId) {
        if (!OrderStatus.ORDERED.name().equalsIgnoreCase(order.getOrderStatus())
                && !"PENDING".equalsIgnoreCase(order.getOrderStatus())) {
            throw new AppException("Only pending orders can currently be cancelled", HttpStatus.CONFLICT);
        }
        double amountPaid = order.getPayment() == null || order.getPayment().getAmountPaid() == null
                ? 0.0 : order.getPayment().getAmountPaid();
        if (amountPaid > 0) throw new AppException("An order with a recorded payment cannot be cancelled until refund handling is available", HttpStatus.CONFLICT);
        if (!Boolean.TRUE.equals(order.getStockReserved())) {
            throw new AppException("This order no longer holds reserved stock", HttpStatus.CONFLICT);
        }
        if (order.getItems() == null || order.getItems().isEmpty()) {
            throw new AppException("Order has no item records to release", HttpStatus.CONFLICT);
        }

        List<OrderItem> orderedItems = new ArrayList<>(order.getItems());
        orderedItems.sort(java.util.Comparator.comparing((OrderItem item) -> item.getProductId() == null ? "" : item.getProductId())
                .thenComparing(item -> item.getVariantId() == null ? "" : item.getVariantId()));
        for (OrderItem item : orderedItems) releaseStock(item);

        String previousStatus = order.getOrderStatus();
        LocalDateTime cancelledAt = LocalDateTime.now();
        order.setOrderStatus(OrderStatus.CANCELED.name());
        order.setCancelledAt(cancelledAt);
        order.setStockReserved(false);
        order.setUpdatedAt(cancelledAt);
        List<OrderStatusEvent> history = order.getStatusHistory() == null
                ? new ArrayList<>() : new ArrayList<>(order.getStatusHistory());
        OrderStatusEvent event = new OrderStatusEvent();
        event.setStatus(OrderStatus.CANCELED.name());
        event.setChangedAt(cancelledAt);
        event.setChangedBy(actorId);
        event.setNote("Order cancelled; reserved stock released");
        history.add(event);
        order.setStatusHistory(history);
        Order saved = orders.save(order);
        redisCache.invalidateRegionAfterCommit("public-products");
        broadcastStatusChange(saved.getId(), previousStatus, OrderStatus.CANCELED.name());
        return saved;
    }

    private void releaseStock(OrderItem item) {
        if (item.getQuantity() == null || item.getQuantity() <= 0
                || !ObjectId.isValid(item.getProductId()) || !ObjectId.isValid(item.getVariantId())) {
            throw new AppException("Order item identifiers or quantities are invalid; reserved stock cannot be released", HttpStatus.CONFLICT);
        }
        ObjectId productId = new ObjectId(item.getProductId());
        String variantId = new ObjectId(item.getVariantId()).toHexString();
        Query variantQuery = Query.query(Criteria.where("_id").is(productId)
                .and("variants").elemMatch(Criteria.where("_id").is(variantId)));
        com.ebs.biocrop.entity.Product restoredProduct = mongoTemplate.findAndModify(
                variantQuery,
                new Update().inc("variants.$.stock", item.getQuantity()),
                FindAndModifyOptions.options().returnNew(true),
                com.ebs.biocrop.entity.Product.class);
        if (restoredProduct != null && restoredProduct.getVariants() != null) {
            for (com.ebs.biocrop.entity.ProductVariant v : restoredProduct.getVariants()) {
                if (variantId.equals(v.getId())) {
                    webSocketService.broadcast("inventory:stock_update", Map.of(
                            "productId", item.getProductId(),
                            "variantId", variantId,
                            "stock", v.getStock() != null ? v.getStock() : 0
                    ));
                    break;
                }
            }
        } else {
            boolean productStillExists = mongoTemplate.exists(
                    Query.query(Criteria.where("_id").is(productId)), com.ebs.biocrop.entity.Product.class);
            boolean variantStillExists = productStillExists && mongoTemplate.exists(
                    Query.query(Criteria.where("_id").is(productId).and("variants._id").is(variantId)),
                    com.ebs.biocrop.entity.Product.class);
            if (!productStillExists) {
                throw new AppException("A product from this order no longer exists; reserved stock cannot be released", HttpStatus.CONFLICT);
            }
            if (!variantStillExists) {
                log.warn("Order cancellation {}: variant {} was removed from product {}; no active stock record remains to restore.",
                        item.getId(), item.getVariantId(), item.getProductId());
            }
        }
    }

    private void broadcastStatusChange(String orderId, String oldStatus, String newStatus) {
        if (orderId == null) return;
        Map<String, Object> payload = Map.of(
                "orderId", orderId,
                "oldStatus", oldStatus != null ? oldStatus : "",
                "newStatus", newStatus != null ? newStatus : ""
        );
        webSocketService.broadcast("orders:status_change", payload);
    }
}
