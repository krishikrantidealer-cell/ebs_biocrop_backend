package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.OrderQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;


@Service
public class OrderQueryServiceImpl implements OrderQueryService {
    private final OrderRepository orders;
    private final UserRepository users;
    private final MongoTemplate mongoTemplate;

    public OrderQueryServiceImpl(OrderRepository orders, UserRepository users, MongoTemplate mongoTemplate) {
        this.orders = orders;
        this.users = users;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Page<Order> listForCustomer(String customerId, int page, int size) {
        claimGuestOrdersForCustomer(customerId);
        return orders.findByCustomerId(customerId,
                PageRequestSupport.create(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Override
    public Order getForCustomer(String customerId, String orderId) {
        return orders.findByIdAndCustomerId(orderId, customerId)
                .orElseGet(() -> {
                    claimGuestOrdersForCustomer(customerId);
                    return orders.findByIdAndCustomerId(orderId, customerId)
                            .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
                });
    }

    @Override
    public Order getCheckoutGroupForCustomer(String customerId, String checkoutGroupId) {
        return orders.findByCustomerIdAndCheckoutGroupId(customerId, checkoutGroupId)
                .orElseGet(() -> {
                    claimGuestOrdersForCustomer(customerId);
                    return orders.findByCustomerIdAndCheckoutGroupId(customerId, checkoutGroupId)
                            .orElseThrow(() -> new ResourceNotFoundException("Order", "checkoutGroupId", checkoutGroupId));
                });
    }

    /**
     * Links guest checkout orders (e.g. Fastrr orders placed before logging in)
     * whose phone number matches this customer's verified phone number.
     */
    private void claimGuestOrdersForCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) return;
        User customer = users.findById(customerId).orElse(null);
        if (customer == null || customer.getPhoneNumber() == null || customer.getPhoneNumber().isBlank()) {
            return;
        }
        Set<String> phoneVariants = phoneVariants(customer.getPhoneNumber());
        if (phoneVariants.isEmpty()) return;

        Query query = Query.query(new Criteria().andOperator(
                new Criteria().orOperator(
                        Criteria.where("customerId").is(null),
                        Criteria.where("customerId").is("")),
                new Criteria().orOperator(
                        Criteria.where("customerSnapshot.phoneNumber").in(phoneVariants),
                        Criteria.where("shippingAddress.phoneNumber").in(phoneVariants))));
        mongoTemplate.updateMulti(query, new Update().set("customerId", customerId), Order.class);
    }

    private static Set<String> phoneVariants(String rawPhone) {
        Set<String> variants = new LinkedHashSet<>();
        String trimmed = rawPhone.trim();
        if (!trimmed.isEmpty()) {
            variants.add(trimmed);
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.length() >= 10) {
            String last10 = digits.substring(digits.length() - 10);
            variants.add(last10);
            variants.add("+91" + last10);
            variants.add("91" + last10);
            variants.add("0" + last10);
        }
        return variants;
    }

    @Override
    public Page<Order> listForAdmin(int page, int size) {
        return listForAdmin(null, page, size);
    }

    @Override
    public Page<Order> listForAdmin(String sellerId, int page, int size) {
        Pageable pageable = PageRequestSupport.create(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        if (sellerId != null && !sellerId.isBlank()) {
            return orders.findBySellerFulfillmentsSellerId(sellerId.trim(), pageable);
        }
        return orders.findAll(pageable);
    }

    @Override
    public Order getForAdmin(String orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
    }

    @Override
    public Order getCheckoutGroupForAdmin(String checkoutGroupId) {
        if (checkoutGroupId == null || checkoutGroupId.isBlank()) {
            throw new ResourceNotFoundException("Order", "checkoutGroupId", checkoutGroupId);
        }
        return orders.findByCheckoutGroupId(checkoutGroupId.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Order", "checkoutGroupId", checkoutGroupId));
    }

    @Override
    public Page<Order> listForSeller(String sellerId, int page, int size) {
        return orders.findBySellerFulfillmentsSellerId(sellerId,
                PageRequestSupport.create(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Override
    public Order getForSeller(String sellerId, String orderId) {
        return orders.findByIdAndSellerFulfillmentsSellerId(orderId, sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
    }
}
