package com.ebs.biocrop.service.impl;

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

    public OrderLifecycleServiceImpl(OrderRepository orders, MongoTemplate mongoTemplate, RedisJsonCache redisCache) {
        this.orders = orders;
        this.mongoTemplate = mongoTemplate;
        this.redisCache = redisCache;
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
    public Order cancelForSeller(String sellerId, String orderId) {
        Order order = orders.findByIdAndSellerId(orderId, sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
        return cancel(order, sellerId);
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
        return saved;
    }

    private void releaseStock(OrderItem item) {
        if (item.getQuantity() == null || item.getQuantity() <= 0
                || !ObjectId.isValid(item.getProductId()) || !ObjectId.isValid(item.getVariantId())) {
            throw new AppException("Order item identifiers or quantities are invalid; reserved stock cannot be released", HttpStatus.CONFLICT);
        }
        ObjectId productId = new ObjectId(item.getProductId());
        ObjectId variantId = new ObjectId(item.getVariantId());
        Query variantQuery = Query.query(Criteria.where("_id").is(productId)
                .and("variants").elemMatch(Criteria.where("_id").is(variantId)));
        UpdateResult result = mongoTemplate.updateFirst(variantQuery,
                new Update().inc("variants.$.stock", item.getQuantity()), com.ebs.biocrop.entity.Product.class);
        if (result.getModifiedCount() != 1) {
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
}
