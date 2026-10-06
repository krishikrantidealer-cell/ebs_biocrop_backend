package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Order;

public interface OrderLifecycleService {
    Order cancelForCustomer(String customerId, String orderId);
    Order cancelForSeller(String sellerId, String orderId);
}
