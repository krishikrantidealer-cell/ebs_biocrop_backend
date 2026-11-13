package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends MongoRepository<Order, String> {
    Optional<Order> findByIdAndCustomerId(String id, String customerId);
    Optional<Order> findByIdAndSellerFulfillmentsSellerId(String id, String sellerId);
    Page<Order> findByCustomerId(String customerId, Pageable pageable);
    Optional<Order> findByCustomerIdAndCheckoutGroupId(String customerId, String checkoutGroupId);
    Optional<Order> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);
    Optional<Order> findByPaymentProviderOrderId(String providerOrderId);
    Page<Order> findBySellerFulfillmentsSellerId(String sellerId, Pageable pageable);
    Optional<Order> findByCheckoutGroupId(String checkoutGroupId);

    @Query(value = "{ 'customerId': ?0, 'items.productId': ?1, 'overallStatus': { '$ne': ?2 } }", exists = true)
    boolean existsByCustomerIdAndProductIdPurchased(String customerId, String productId, String excludedStatus);
}
