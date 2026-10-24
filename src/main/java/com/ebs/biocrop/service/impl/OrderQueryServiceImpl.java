package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.service.OrderQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrderQueryServiceImpl implements OrderQueryService {
    private final OrderRepository orders;

    public OrderQueryServiceImpl(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Page<Order> listForCustomer(String customerId, int page, int size) {
        return orders.findByCustomerId(customerId,
                PageRequestSupport.create(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Override
    public Order getForCustomer(String customerId, String orderId) {
        return orders.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
    }

    @Override
    public List<Order> getCheckoutGroupForCustomer(String customerId, String checkoutGroupId) {
        return orders.findByCustomerIdAndCheckoutGroupId(customerId, checkoutGroupId);
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
            return orders.findBySellerId(sellerId.trim(), pageable);
        }
        return orders.findAll(pageable);
    }

    @Override
    public Order getForAdmin(String orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
    }

    @Override
    public List<Order> getCheckoutGroupForAdmin(String checkoutGroupId) {
        if (checkoutGroupId == null || checkoutGroupId.isBlank()) {
            return List.of();
        }
        return orders.findByCheckoutGroupId(checkoutGroupId.trim());
    }

    @Override
    public Page<Order> listForSeller(String sellerId, int page, int size) {
        return orders.findBySellerId(sellerId,
                PageRequestSupport.create(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Override
    public Order getForSeller(String sellerId, String orderId) {
        return orders.findByIdAndSellerId(orderId, sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));
    }
}
