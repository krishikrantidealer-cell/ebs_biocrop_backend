package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.OrderStatus;

public interface OrderLifecycleService {
    Order cancelForCustomer(String customerId, String orderId);
    Order cancelForAdmin(String adminId, String orderId);
    Order updateStatusForAdmin(String adminId, String orderId, OrderStatus status, String note);
}
