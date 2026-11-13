package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.entity.*;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.service.ShiprocketService;
import com.ebs.biocrop.websocket.WebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderLifecycleServiceImplTest {

    private static final String ORDER_ID = "order-123";
    private static final String CUSTOMER_ID = "cust-1";
    private static final String SELLER_1_ID = "seller-1";
    private static final String SELLER_2_ID = "seller-2";
    private static final String FULFILLMENT_1_ID = "ful-1";
    private static final String FULFILLMENT_2_ID = "ful-2";
    private static final String ITEM_1_ID = "item-1";
    private static final String ITEM_2_ID = "item-2";

    @Mock private OrderRepository orders;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private RedisJsonCache redisCache;
    @Mock private WebSocketService webSocketService;
    @Mock private ShiprocketService shiprocketService;

    private OrderLifecycleServiceImpl lifecycleService;
    private Order multiSellerOrder;

    @BeforeEach
    void setUp() {
        lifecycleService = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService);

        multiSellerOrder = new Order();
        multiSellerOrder.setId(ORDER_ID);
        multiSellerOrder.setCustomerId(CUSTOMER_ID);
        multiSellerOrder.setOverallStatus("AWAITING_SELLER_RESPONSE");

        // Financials & Payment
        OrderPaymentDetails payment = new OrderPaymentDetails();
        payment.setPaymentType("PARTIAL_ADVANCE_COD");
        payment.setStatus("PARTIALLY_PAID");
        payment.setAdvanceAmountDue(new BigDecimal("100.00"));
        payment.setAdvanceAmountPaid(new BigDecimal("100.00"));
        payment.setAmountPaid(new BigDecimal("100.00"));
        payment.setCodDue(new BigDecimal("900.00"));
        payment.setRemainingAmount(new BigDecimal("900.00"));
        multiSellerOrder.setPayment(payment);

        Order.FinancialSummary summary = new Order.FinancialSummary();
        summary.setCurrentOrderTotal(new BigDecimal("1000.00"));
        summary.setCancelledAmount(BigDecimal.ZERO);
        summary.setCodDue(new BigDecimal("900.00"));
        multiSellerOrder.setFinancialSummary(summary);

        // Fulfillments
        Order.SellerFulfillment f1 = new Order.SellerFulfillment();
        f1.setId(FULFILLMENT_1_ID);
        f1.setSellerId(SELLER_1_ID);
        f1.setStatus("AWAITING_SELLER_RESPONSE");
        f1.setItemOrderIds(List.of(ITEM_1_ID));
        f1.setResponseDeadlineAt(LocalDateTime.now().plusHours(12));

        Order.SellerFulfillment f2 = new Order.SellerFulfillment();
        f2.setId(FULFILLMENT_2_ID);
        f2.setSellerId(SELLER_2_ID);
        f2.setStatus("AWAITING_SELLER_RESPONSE");
        f2.setItemOrderIds(List.of(ITEM_2_ID));
        f2.setResponseDeadlineAt(LocalDateTime.now().plusHours(12));

        multiSellerOrder.setSellerFulfillments(new ArrayList<>(List.of(f1, f2)));

        // Items
        OrderItem i1 = new OrderItem();
        i1.setItemOrderId(ITEM_1_ID);
        i1.setSellerFulfillmentId(FULFILLMENT_1_ID);
        i1.setSellerId(SELLER_1_ID);
        i1.setProductId("prod-1");
        i1.setVariantId("var-1");
        i1.setQuantity(1);
        i1.setLineTotal(new BigDecimal("500.00"));
        i1.setAdvanceAllocatedAtCheckout(new BigDecimal("50.00"));
        i1.setCodDueAtCheckout(new BigDecimal("450.00"));
        i1.setItemStatus("AWAITING_SELLER_RESPONSE");

        OrderItem i2 = new OrderItem();
        i2.setItemOrderId(ITEM_2_ID);
        i2.setSellerFulfillmentId(FULFILLMENT_2_ID);
        i2.setSellerId(SELLER_2_ID);
        i2.setProductId("prod-2");
        i2.setVariantId("var-2");
        i2.setQuantity(1);
        i2.setLineTotal(new BigDecimal("500.00"));
        i2.setAdvanceAllocatedAtCheckout(new BigDecimal("50.00"));
        i2.setCodDueAtCheckout(new BigDecimal("450.00"));
        i2.setItemStatus("AWAITING_SELLER_RESPONSE");

        multiSellerOrder.setItems(new ArrayList<>(List.of(i1, i2)));

        lenient().when(orders.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Checklist 1: Seller can accept/reject only their own fulfillment group")
    void testSellerCanOnlyDecideOwnFulfillment() {
        when(orders.findByIdAndSellerFulfillmentsSellerId(ORDER_ID, SELLER_1_ID))
                .thenReturn(Optional.of(multiSellerOrder));

        // Seller 1 tries to accept Seller 2's fulfillment -> should fail with ResourceNotFoundException (404)
        assertThrows(ResourceNotFoundException.class, () ->
                lifecycleService.respondToSellerFulfillment(SELLER_1_ID, ORDER_ID, FULFILLMENT_2_ID, "ACCEPT"));

        // Seller 1 accepts their own fulfillment -> succeeds
        Order updated = lifecycleService.respondToSellerFulfillment(SELLER_1_ID, ORDER_ID, FULFILLMENT_1_ID, "ACCEPT");
        assertEquals("ACCEPTED", updated.getSellerFulfillments().get(0).getStatus());
        assertEquals("AWAITING_SELLER_RESPONSE", updated.getSellerFulfillments().get(1).getStatus());
        assertEquals("AWAITING_SELLER_RESPONSE", updated.getOverallStatus(), "Overall status stays awaiting seller because seller 2 is pending");
    }

    @Test
    @DisplayName("Checklist 2: 12-hour response deadline and 48-hour dispatch SLA deadline are enforced")
    void testDeadlinesEnforced() {
        when(orders.findByIdAndSellerFulfillmentsSellerId(ORDER_ID, SELLER_1_ID))
                .thenReturn(Optional.of(multiSellerOrder));

        // Expire the 12-hour response deadline
        multiSellerOrder.getSellerFulfillments().get(0).setResponseDeadlineAt(LocalDateTime.now().minusMinutes(5));

        AppException expiredEx = assertThrows(AppException.class, () ->
                lifecycleService.respondToSellerFulfillment(SELLER_1_ID, ORDER_ID, FULFILLMENT_1_ID, "ACCEPT"));
        assertEquals(HttpStatus.CONFLICT, expiredEx.getStatus());
        assertTrue(expiredEx.getMessage().contains("expired"));

        // Now test 48-hour dispatch SLA deadline on packing
        multiSellerOrder.getSellerFulfillments().get(0).setStatus("ACCEPTED");
        multiSellerOrder.getSellerFulfillments().get(0).setDispatchSlaDeadlineAt(LocalDateTime.now().minusHours(1));

        AppException slaEx = assertThrows(AppException.class, () ->
                lifecycleService.markSellerFulfillmentPacked(SELLER_1_ID, ORDER_ID, FULFILLMENT_1_ID));
        assertEquals(HttpStatus.CONFLICT, slaEx.getStatus());
        assertTrue(slaEx.getMessage().contains("Dispatch SLA has expired"));
    }

    @Test
    @DisplayName("Checklist 3: Rejection affects that seller's items only; other sellers continue")
    void testRejectionAffectsOnlyTargetSellerItems() {
        when(orders.findByIdAndSellerFulfillmentsSellerId(ORDER_ID, SELLER_1_ID))
                .thenReturn(Optional.of(multiSellerOrder));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Product.class))).thenReturn(new Product());

        Order updated = lifecycleService.respondToSellerFulfillment(SELLER_1_ID, ORDER_ID, FULFILLMENT_1_ID, "REJECT");

        // Seller 1 fulfillment and item are cancelled/rejected
        assertEquals("REJECTED", updated.getSellerFulfillments().get(0).getStatus());
        assertEquals("CANCELLED_BY_SELLER", updated.getItems().get(0).getItemStatus());

        // Seller 2 fulfillment and item are still active and untouched
        assertEquals("AWAITING_SELLER_RESPONSE", updated.getSellerFulfillments().get(1).getStatus());
        assertEquals("AWAITING_SELLER_RESPONSE", updated.getItems().get(1).getItemStatus());

        // Overall status requires customer decision for the rejected seller
        assertEquals("CUSTOMER_DECISION_REQUIRED", updated.getOverallStatus());
        assertEquals(1, updated.getCustomerDecisionWindows().size());
        assertEquals(FULFILLMENT_1_ID, updated.getCustomerDecisionWindows().get(0).getSellerFulfillmentId());
    }

    @Test
    @DisplayName("Checklist 4: Customer receives 6-hour choice window with default CONTINUE on expiration")
    void testCustomerChoiceWindowAndDefaultContinue() {
        // 1. Check window duration is 6 hours on seller rejection
        when(orders.findByIdAndSellerFulfillmentsSellerId(ORDER_ID, SELLER_1_ID))
                .thenReturn(Optional.of(multiSellerOrder));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Product.class))).thenReturn(new Product());

        LocalDateTime beforeRejection = LocalDateTime.now();
        Order updated = lifecycleService.respondToSellerFulfillment(SELLER_1_ID, ORDER_ID, FULFILLMENT_1_ID, "REJECT");
        Order.CustomerDecisionWindow window = updated.getCustomerDecisionWindows().get(0);

        assertTrue(window.getDeadlineAt().isAfter(beforeRejection.plusHours(5).plusMinutes(50)));
        assertTrue(window.getDeadlineAt().isBefore(beforeRejection.plusHours(6).plusMinutes(10)));
        assertEquals("OPEN", window.getStatus());

        // 2. Expire window and trigger scheduler sweep
        window.setDeadlineAt(LocalDateTime.now().minusMinutes(1));
        when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of(updated));

        lifecycleService.continueOrdersAfterExpiredCustomerDecisionWindows();

        assertEquals("RESOLVED", window.getStatus());
        assertEquals("CONTINUE", window.getResolution());
        assertEquals("AWAITING_SELLER_RESPONSE", updated.getOverallStatus(), "Remaining seller fulfillments continue");
    }

    @Test
    @DisplayName("Checklist 5: CANCEL_ITEMS, CANCEL_ORDER, and CONTINUE update states consistently and release stock once")
    void testCustomerDecisionsAndStockRelease() {
        when(orders.findByIdAndCustomerId(ORDER_ID, CUSTOMER_ID)).thenReturn(Optional.of(multiSellerOrder));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Product.class))).thenReturn(new Product());

        // Setup open decision window
        Order.CustomerDecisionWindow window = new Order.CustomerDecisionWindow();
        window.setDecisionWindowId("win-1");
        window.setStatus("OPEN");
        window.setDeadlineAt(LocalDateTime.now().plusHours(5));
        window.setSellerFulfillmentId(FULFILLMENT_1_ID);
        window.setAffectedItemOrderIds(List.of(ITEM_1_ID));
        multiSellerOrder.setCustomerDecisionWindows(new ArrayList<>(List.of(window)));

        // Mark item 1 rejected by seller (stock already released for item 1)
        multiSellerOrder.getItems().get(0).setItemStatus("CANCELLED_BY_SELLER");

        // Customer chooses CANCEL_ITEMS for item 2
        Order resolved = lifecycleService.resolveCustomerDecisionWindow(
                CUSTOMER_ID, ORDER_ID, "win-1", "CANCEL_ITEMS", List.of(ITEM_2_ID));

        assertEquals("RESOLVED", window.getStatus());
        assertEquals("CANCEL_ITEMS", window.getResolution());
        assertEquals("CANCELLED_BY_CUSTOMER", resolved.getItems().get(1).getItemStatus());
        assertEquals("CANCELLED", resolved.getOverallStatus(), "All items cancelled -> order cancelled");

        // Verify stock release called only once for item 2
        verify(mongoTemplate, times(1)).findAndModify(any(), any(), any(), eq(Product.class));
    }

    @Test
    @DisplayName("Checklist 6: Scheduled timeout handling is repeat-safe (idempotent reruns)")
    void testScheduledTimeoutsRepeatSafe() {
        // Setup order with expired seller response deadline
        multiSellerOrder.getSellerFulfillments().get(0).setResponseDeadlineAt(LocalDateTime.now().minusMinutes(5));
        when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of(multiSellerOrder));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Product.class))).thenReturn(new Product());

        // First run
        lifecycleService.processExpiredSellerResponseWindows();
        assertEquals("EXPIRED_TIMEOUT", multiSellerOrder.getSellerFulfillments().get(0).getStatus());
        assertEquals(1, multiSellerOrder.getCustomerDecisionWindows().size());
        int historyCountAfterFirstRun = multiSellerOrder.getStatusHistory().size();

        // Second run on the same order (simulating next minute's scheduled execution)
        lifecycleService.processExpiredSellerResponseWindows();

        // Must remain exactly 1 window, status unchanged, and no duplicate history events
        assertEquals(1, multiSellerOrder.getCustomerDecisionWindows().size());
        assertEquals(historyCountAfterFirstRun, multiSellerOrder.getStatusHistory().size(),
                "Repeated scheduler sweeps must not append duplicate status history events");
    }

    @Test
    @DisplayName("Shiprocket: Book shipment embeds into sellerFulfillments[].shipments[] without separate order documents")
    void testBookSellerFulfillmentShipmentEmbedded() {
        lifecycleService = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService, shiprocketService);
        makeItemsPrepaidForShiprocket(multiSellerOrder, List.of(ITEM_1_ID));
        when(orders.findByIdAndSellerFulfillmentsSellerId(ORDER_ID, SELLER_1_ID))
                .thenReturn(Optional.of(multiSellerOrder));
        when(shiprocketService.createFulfillmentShipment(eq(multiSellerOrder), eq(FULFILLMENT_1_ID),
                eq(List.of(ITEM_1_ID)), any(Order.Shipment.class)))
                .thenReturn(new com.ebs.biocrop.dto.response.ShiprocketOrderResult(
                        123L, 456L, "SRK12345678IN", "Test Courier", "10",
                        "https://shiprocket.co/tracking/SRK12345678IN", "READY_FOR_PICKUP"));

        // Accept fulfillment 1 first
        multiSellerOrder.getSellerFulfillments().get(0).setStatus("ACCEPTED");
        multiSellerOrder.getSellerFulfillments().get(0).setDispatchSlaDeadlineAt(LocalDateTime.now().plusHours(48));

        Order updated = lifecycleService.bookSellerFulfillmentShipment(SELLER_1_ID, ORDER_ID, FULFILLMENT_1_ID,
                null, packageDetails());

        // Verify fulfillment 1 is PACKED and has embedded shipment
        Order.SellerFulfillment f1 = updated.getSellerFulfillments().get(0);
        assertEquals("PACKED", f1.getStatus());
        assertEquals(1, f1.getShipments().size(), "Shipment must be embedded in seller fulfillment");
        Order.Shipment shipment = f1.getShipments().get(0);
        assertEquals("READY_FOR_PICKUP", shipment.getStatus());
        assertNotNull(shipment.getTrackingNumber());
        assertNotNull(shipment.getCarrier());

        // Fulfillment 2 must not have any shipment
        assertEquals(0, updated.getSellerFulfillments().get(1).getShipments().size());

        // Unified order document is saved once
        verify(orders, atLeastOnce()).save(multiSellerOrder);
    }

    @Test
    @DisplayName("Shiprocket: fulfillment items can be split across packages and cannot be booked twice")
    void testFulfillmentItemsCanBeSplitAcrossShipmentsWithoutOverlap() {
        lifecycleService = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService, shiprocketService);
        makeItemsPrepaidForShiprocket(multiSellerOrder, List.of(ITEM_1_ID));
        when(orders.findByIdAndSellerFulfillmentsSellerId(ORDER_ID, SELLER_1_ID))
                .thenReturn(Optional.of(multiSellerOrder));
        Order.SellerFulfillment fulfillment = multiSellerOrder.getSellerFulfillments().get(0);
        fulfillment.setStatus("ACCEPTED");
        fulfillment.setDispatchSlaDeadlineAt(LocalDateTime.now().plusHours(48));
        OrderItem extraItem = new OrderItem();
        extraItem.setItemOrderId("item-1b");
        extraItem.setSellerFulfillmentId(FULFILLMENT_1_ID);
        extraItem.setSellerId(SELLER_1_ID);
        extraItem.setItemStatus("PROCESSING");
        multiSellerOrder.getItems().add(extraItem);
        fulfillment.setItemOrderIds(List.of(ITEM_1_ID, "item-1b"));
        when(shiprocketService.createFulfillmentShipment(eq(multiSellerOrder), eq(FULFILLMENT_1_ID),
                eq(List.of(ITEM_1_ID)), any(Order.Shipment.class)))
                .thenReturn(new com.ebs.biocrop.dto.response.ShiprocketOrderResult(
                        123L, 456L, "SRK11111111IN", "Test Courier", "10", "https://tracking.test/1", "READY_FOR_PICKUP"));
        when(shiprocketService.createFulfillmentShipment(eq(multiSellerOrder), eq(FULFILLMENT_1_ID),
                eq(List.of("item-1b")), any(Order.Shipment.class)))
                .thenReturn(new com.ebs.biocrop.dto.response.ShiprocketOrderResult(
                        124L, 457L, "SRK22222222IN", "Test Courier", "10", "https://tracking.test/2", "READY_FOR_PICKUP"));

        lifecycleService.bookSellerFulfillmentShipment(SELLER_1_ID, ORDER_ID, FULFILLMENT_1_ID,
                List.of(ITEM_1_ID), packageDetails());
        assertEquals("ACCEPTED", fulfillment.getStatus(), "The group stays open while a seller item is not yet packaged");
        lifecycleService.bookSellerFulfillmentShipment(SELLER_1_ID, ORDER_ID, FULFILLMENT_1_ID,
                List.of("item-1b"), packageDetails());

        assertEquals("PACKED", fulfillment.getStatus());
        assertEquals(2, fulfillment.getShipments().size());
        assertEquals(List.of(ITEM_1_ID), fulfillment.getShipments().get(0).getItemOrderIds());
        assertEquals(List.of("item-1b"), fulfillment.getShipments().get(1).getItemOrderIds());
        assertThrows(AppException.class, () -> lifecycleService.bookSellerFulfillmentShipment(
                SELLER_1_ID, ORDER_ID, FULFILLMENT_1_ID, List.of(ITEM_1_ID), packageDetails()));
        verify(shiprocketService, times(2)).createFulfillmentShipment(eq(multiSellerOrder), eq(FULFILLMENT_1_ID),
                anyList(), any(Order.Shipment.class));
    }

    @Test
    @DisplayName("Shiprocket: missing integration fails closed instead of fabricating an AWB")
    void testBookSellerFulfillmentShipmentFailsWhenShiprocketMissing() {
        when(orders.findByIdAndSellerFulfillmentsSellerId(ORDER_ID, SELLER_1_ID))
                .thenReturn(Optional.of(multiSellerOrder));
        Order.SellerFulfillment fulfillment = multiSellerOrder.getSellerFulfillments().get(0);
        fulfillment.setStatus("ACCEPTED");
        fulfillment.setDispatchSlaDeadlineAt(LocalDateTime.now().plusHours(48));

        assertThrows(AppException.class,
                () -> lifecycleService.bookSellerFulfillmentShipment(SELLER_1_ID, ORDER_ID, FULFILLMENT_1_ID,
                        null, packageDetails()));
        assertTrue(fulfillment.getShipments() == null || fulfillment.getShipments().isEmpty());
        verify(orders, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("Shiprocket: an ambiguous booking result stays reserved and cannot be blindly retried")
    void testAmbiguousShipmentBookingRequiresReconciliation() {
        lifecycleService = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService, shiprocketService);
        makeItemsPrepaidForShiprocket(multiSellerOrder, List.of(ITEM_1_ID));
        when(orders.findByIdAndSellerFulfillmentsSellerId(ORDER_ID, SELLER_1_ID))
                .thenReturn(Optional.of(multiSellerOrder));
        when(orders.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(shiprocketService.createFulfillmentShipment(eq(multiSellerOrder), eq(FULFILLMENT_1_ID),
                eq(List.of(ITEM_1_ID)), any(Order.Shipment.class)))
                .thenThrow(new IllegalStateException("simulated timeout after remote request"));
        Order.SellerFulfillment fulfillment = multiSellerOrder.getSellerFulfillments().get(0);
        fulfillment.setStatus("ACCEPTED");
        fulfillment.setDispatchSlaDeadlineAt(LocalDateTime.now().plusHours(48));

        assertThrows(AppException.class, () -> lifecycleService.bookSellerFulfillmentShipment(
                SELLER_1_ID, ORDER_ID, FULFILLMENT_1_ID, List.of(ITEM_1_ID), packageDetails()));

        assertEquals("BOOKING_REQUIRES_RECONCILIATION", fulfillment.getShipments().get(0).getStatus());
        assertNotNull(fulfillment.getShipments().get(0).getBookingReference());
        assertThrows(AppException.class, () -> lifecycleService.bookSellerFulfillmentShipment(
                SELLER_1_ID, ORDER_ID, FULFILLMENT_1_ID, List.of(ITEM_1_ID), packageDetails()));
        verify(shiprocketService, times(1)).createFulfillmentShipment(eq(multiSellerOrder), eq(FULFILLMENT_1_ID),
                eq(List.of(ITEM_1_ID)), any(Order.Shipment.class));
        verify(orders, times(2)).save(multiSellerOrder);
    }

    @Test
    @DisplayName("Shiprocket: partial COD booking is allowed with its captured advance and remaining balance")
    void partialCodShipmentCarriesItsCapturedAdvanceAndBalance() {
        lifecycleService = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService, shiprocketService);
        when(orders.findByIdAndSellerFulfillmentsSellerId(ORDER_ID, SELLER_1_ID))
                .thenReturn(Optional.of(multiSellerOrder));
        Order.SellerFulfillment fulfillment = multiSellerOrder.getSellerFulfillments().get(0);
        fulfillment.setStatus("ACCEPTED");
        fulfillment.setDispatchSlaDeadlineAt(LocalDateTime.now().plusHours(48));
        multiSellerOrder.getItems().get(0).setAdvanceAllocatedAtCheckout(new BigDecimal("20.00"));
        multiSellerOrder.getItems().get(0).setCodDueAtCheckout(new BigDecimal("190.00"));
        when(shiprocketService.createFulfillmentShipment(eq(multiSellerOrder), eq(FULFILLMENT_1_ID),
                eq(List.of(ITEM_1_ID)), any(Order.Shipment.class)))
                .thenReturn(new com.ebs.biocrop.dto.response.ShiprocketOrderResult(
                        123L, 456L, "AWB-PARTIAL-123", "Test Courier", "10", "https://tracking.test/partial", "READY_FOR_PICKUP"));

        Order updated = lifecycleService.bookSellerFulfillmentShipment(
                SELLER_1_ID, ORDER_ID, FULFILLMENT_1_ID, List.of(ITEM_1_ID), packageDetails());

        assertEquals("AWB-PARTIAL-123", updated.getSellerFulfillments().get(0).getShipments().get(0).getTrackingNumber());
        verify(shiprocketService).createFulfillmentShipment(eq(multiSellerOrder), eq(FULFILLMENT_1_ID),
                eq(List.of(ITEM_1_ID)), any(Order.Shipment.class));
    }

    @Test
    @DisplayName("Shiprocket: Carrier handover confirms pickup timestamp and marks fulfillment/items DISPATCHED")
    void testCarrierHandoverMarksHandoverAndDispatch() {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(multiSellerOrder));

        // Setup packed fulfillment 1 with booked shipment
        Order.SellerFulfillment f1 = multiSellerOrder.getSellerFulfillments().get(0);
        f1.setStatus("PACKED");
        Order.Shipment shipment = new Order.Shipment();
        shipment.setShipmentId("sr-12345");
        shipment.setTrackingNumber("SRK12345678IN");
        shipment.setStatus("READY_FOR_PICKUP");
        shipment.setItemOrderIds(List.of(ITEM_1_ID));
        f1.setShipments(new ArrayList<>(List.of(shipment)));

        LocalDateTime handoverTime = LocalDateTime.now();
        Order updated = lifecycleService.recordCarrierHandover(ORDER_ID, FULFILLMENT_1_ID, "SRK12345678IN", handoverTime);

        // Fulfillment 1 and item 1 become DISPATCHED
        assertEquals("DISPATCHED", f1.getStatus());
        assertEquals(handoverTime, f1.getDispatchedAt());
        assertEquals(handoverTime, shipment.getPickupConfirmedAt());
        assertEquals("IN_TRANSIT", shipment.getStatus());
        assertEquals("DISPATCHED", updated.getItems().get(0).getItemStatus());

        // Fulfillment 2 and item 2 are unaffected
        assertEquals("AWAITING_SELLER_RESPONSE", updated.getSellerFulfillments().get(1).getStatus());
        assertEquals("AWAITING_SELLER_RESPONSE", updated.getItems().get(1).getItemStatus());
    }

    @Test
    @DisplayName("Shiprocket: tracking advances only the package items and is repeat/out-of-order safe")
    void testCarrierTrackingProgressesOnePackageWithoutRegressing() {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(multiSellerOrder));
        Order.SellerFulfillment fulfillment = multiSellerOrder.getSellerFulfillments().get(0);
        fulfillment.setStatus("PACKED");
        Order.Shipment shipment = new Order.Shipment();
        shipment.setShipmentId("sr-12345");
        shipment.setTrackingNumber("SRK12345678IN");
        shipment.setStatus("READY_FOR_PICKUP");
        shipment.setItemOrderIds(List.of(ITEM_1_ID));
        fulfillment.setShipments(new ArrayList<>(List.of(shipment)));

        LocalDateTime pickedUp = LocalDateTime.now();
        lifecycleService.recordCarrierTrackingStatus(ORDER_ID, FULFILLMENT_1_ID, "SRK12345678IN", 42, "PICKED UP", pickedUp);
        lifecycleService.recordCarrierTrackingStatus(ORDER_ID, FULFILLMENT_1_ID, "SRK12345678IN", 17, "OUT FOR DELIVERY", pickedUp.plusHours(1));
        lifecycleService.recordCarrierTrackingStatus(ORDER_ID, FULFILLMENT_1_ID, "SRK12345678IN", 7, "DELIVERED", pickedUp.plusHours(2));

        assertEquals("DELIVERED", shipment.getStatus());
        assertEquals(pickedUp.plusHours(2), shipment.getDeliveredAt());
        assertEquals("DELIVERED", multiSellerOrder.getItems().get(0).getItemStatus());
        assertEquals("AWAITING_SELLER_RESPONSE", multiSellerOrder.getItems().get(1).getItemStatus());
        assertEquals("PARTIALLY_SHIPPED", multiSellerOrder.getOverallStatus());
        int historySize = multiSellerOrder.getStatusHistory().size();

        lifecycleService.recordCarrierTrackingStatus(ORDER_ID, FULFILLMENT_1_ID, "SRK12345678IN", 42, "PICKED UP", pickedUp);
        assertEquals("DELIVERED", shipment.getStatus());
        assertEquals(historySize, multiSellerOrder.getStatusHistory().size());
    }

    @Test
    @DisplayName("Shiprocket: delivery delay events are retained without falsely marking delivery or changing item ownership")
    void carrierDelayEventIsRetainedForTheTargetShipment() {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(multiSellerOrder));
        Order.SellerFulfillment fulfillment = multiSellerOrder.getSellerFulfillments().get(0);
        Order.Shipment shipment = new Order.Shipment();
        shipment.setTrackingNumber("SRK-DELAY-1");
        shipment.setStatus("IN_TRANSIT");
        shipment.setItemOrderIds(List.of(ITEM_1_ID));
        shipment.setLastTrackingUpdateAt(LocalDateTime.now().minusHours(1));
        fulfillment.setShipments(new ArrayList<>(List.of(shipment)));

        LocalDateTime delayedAt = LocalDateTime.now();
        lifecycleService.recordCarrierTrackingStatus(ORDER_ID, FULFILLMENT_1_ID, "SRK-DELAY-1", 22, "DELAYED", delayedAt);

        assertEquals("DELAYED", shipment.getStatus());
        assertEquals("DISPATCHED", multiSellerOrder.getItems().get(0).getItemStatus());
        assertEquals("AWAITING_SELLER_RESPONSE", multiSellerOrder.getItems().get(1).getItemStatus());
        assertEquals("DELAYED", shipment.getLastTrackingDescription());
    }

    private Order.Shipment packageDetails() {
        Order.Shipment details = new Order.Shipment();
        details.setPackageWeightKg(1.0);
        details.setLengthCm(20.0);
        details.setBreadthCm(10.0);
        details.setHeightCm(10.0);
        return details;
    }

    private void makeItemsPrepaidForShiprocket(Order order, List<String> itemOrderIds) {
        for (OrderItem item : order.getItems()) {
            if (itemOrderIds.contains(item.getItemOrderId())) {
                item.setAdvanceAllocatedAtCheckout(item.getLineTotal());
                item.setCodDueAtCheckout(BigDecimal.ZERO);
            }
        }
    }

    @Test
    @DisplayName("recordPaymentAuthorized transitions payment to AUTHORIZED")
    void testRecordPaymentAuthorized() {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(multiSellerOrder));
        when(orders.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = lifecycleService.recordPaymentAuthorized(ORDER_ID, "pay_auth_123");

        assertEquals(com.ebs.biocrop.entity.enums.PaymentStatus.AUTHORIZED.name(), result.getPayment().getStatus());
        assertTrue(result.getPayment().getTransactions().stream()
                .anyMatch(tx -> "pay_auth_123".equals(tx.getProviderPaymentId()) && "AUTHORIZED".equals(tx.getStatus())));
    }

    @Test
    @DisplayName("recordPaymentFailed transitions payment to FAILED")
    void testRecordPaymentFailed() {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(multiSellerOrder));
        when(orders.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = lifecycleService.recordPaymentFailed(ORDER_ID, "pay_fail_123", "Bank server down");

        assertEquals(com.ebs.biocrop.entity.enums.PaymentStatus.FAILED.name(), result.getPayment().getStatus());
        assertTrue(result.getPayment().getTransactions().stream()
                .anyMatch(tx -> "pay_fail_123".equals(tx.getProviderPaymentId()) && "FAILED".equals(tx.getStatus())));
    }

    @Test
    @DisplayName("recordPaymentRefunded transitions payment to REFUNDED and updates financial summary")
    void testRecordPaymentRefunded() {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(multiSellerOrder));
        when(orders.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = lifecycleService.recordPaymentRefunded(ORDER_ID, "rfnd_123", new BigDecimal("100.00"), "Product damaged");

        assertEquals(com.ebs.biocrop.entity.enums.PaymentStatus.REFUNDED.name(), result.getPayment().getStatus());
        assertEquals(new BigDecimal("100.00"), result.getFinancialSummary().getRefundedTotal());
        assertEquals(BigDecimal.ZERO, result.getFinancialSummary().getNetOnlineAmountRetained());
    }
}
