package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Order;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderLifecycleService {
    Order cancelForCustomer(String customerId, String orderId);
    Order cancelForAdmin(String adminId, String orderId);
    /** Called only by a trusted payment-provider adapter after verifying capture. */
    Order recordAdvancePaymentCaptured(String orderId, String providerPaymentReference, java.math.BigDecimal capturedAmount);
    Order respondToSellerFulfillment(String sellerId, String orderId, String fulfillmentId, String decision);
    Order markSellerFulfillmentPacked(String sellerId, String orderId, String fulfillmentId);
    /**
     * Books a Shiprocket courier shipment and generates AWB label for a packed seller fulfillment,
     * embedding the shipment inside the seller's fulfillment without creating separate order documents.
     */
    Order bookSellerFulfillmentShipment(String sellerId, String orderId, String fulfillmentId);
    Order bookSellerFulfillmentShipment(String sellerId, String orderId, String fulfillmentId, List<String> itemOrderIds);
    Order bookSellerFulfillmentShipment(String sellerId, String orderId, String fulfillmentId,
                                        List<String> itemOrderIds, Order.Shipment packageDetails);
    /**
     * Records carrier pickup handover event for a booked fulfillment shipment,
     * transitioning fulfillment and items to DISPATCHED.
     */
    Order recordCarrierHandover(String orderId, String fulfillmentId, String trackingNumber, LocalDateTime handoverTime);
    Order recordCarrierTrackingStatus(String orderId, String fulfillmentId, String trackingNumber,
                                      Integer statusId, String status, LocalDateTime eventTime);
    Order resolveCustomerDecisionWindow(String customerId, String orderId, String decisionWindowId,
                                        String decision, List<String> itemOrderIds);
}
