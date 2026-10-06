package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Order;
import org.springframework.data.domain.Page;

import java.util.List;

public interface OrderQueryService {
    Page<Order> listForCustomer(String customerId, int page, int size);
    Order getForCustomer(String customerId, String orderId);
    List<Order> getCheckoutGroupForCustomer(String customerId, String checkoutGroupId);
    Page<Order> listForSeller(String sellerId, int page, int size);
    Order getForSeller(String sellerId, String orderId);
}
