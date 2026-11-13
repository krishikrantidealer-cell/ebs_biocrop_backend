package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.PlaceOrderRequest;
import com.ebs.biocrop.entity.Cart;
import com.ebs.biocrop.entity.CartItem;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.OrderPaymentMethod;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.PaymentMethod;
import com.ebs.biocrop.dto.response.OrderResponse;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.repository.CartRepository;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.repository.ProductVariantLookup;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.websocket.WebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.bson.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderPlacementServiceImplTest {
    private static final String CUSTOMER_ID = "6ab2318cbe0b0c21399291f7";
    private static final String SELLER_ID = "6ae2d682477dabe731e2c1f2";
    private static final String PRODUCT_ID = "6ad871a11df71c7f599dd932";
    private static final String VARIANT_ID = "f50721088ade35fc47e0a4db";

    @Mock private CartRepository carts;
    @Mock private ProductVariantLookup variantLookup;
    @Mock private UserRepository users;
    @Mock private OrderRepository orders;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private RedisJsonCache redisCache;
    @Mock private WebSocketService webSocketService;

    private OrderPlacementServiceImpl service;
    private Cart cart;
    private Product product;

    @BeforeEach
    void setUp() {
        service = new OrderPlacementServiceImpl(carts, variantLookup, users, orders,
                mongoTemplate, redisCache, webSocketService);

        User customer = User.newCustomer("9876543210");
        customer.setId(CUSTOMER_ID);
        lenient().when(users.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        User seller = User.newSeller("9876501234", "seller@example.com");
        seller.setId(SELLER_ID);
        seller.setShopName("Test Seller");
        lenient().when(users.findAllById(any())).thenReturn(List.of(seller));

        ProductVariant variant = new ProductVariant();
        variant.setId(VARIANT_ID);
        variant.setStock(5);
        variant.setDisplayRate(100.0);
        variant.setPrintedMrp(100.0);
        variant.setIsPartialPaymentAllowed(true);

        product = new Product();
        product.setId(PRODUCT_ID);
        product.setSellerId(SELLER_ID);
        product.setTitle("Test Product");
        product.setStatus("ACTIVE");
        product.setIsAvailable(true);
        product.setIsDeleted(false);
        product.setVariants(List.of(variant));
        lenient().when(variantLookup.findProductsContainingVariants(List.of(VARIANT_ID))).thenReturn(List.of(product));

        cart = new Cart(CUSTOMER_ID);
        cart.setId("cart-id");
        cart.setItems(List.of(new CartItem(PRODUCT_ID, VARIANT_ID, 2, 100.0)));
        lenient().when(mongoTemplate.findAndRemove(any(Query.class), eq(Cart.class))).thenReturn(cart);
        lenient().when(carts.findByUser(CUSTOMER_ID)).thenReturn(Optional.of(cart));
        lenient().when(orders.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, "checkout-key"))
                .thenReturn(Optional.empty());

        lenient().when(orders.save(any(Order.class))).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId("order-id");
                saved.setVersion(0);
            } else {
                saved.setVersion(saved.getVersion() == null ? 0 : saved.getVersion() + 1);
            }
            return saved;
        });
    }

    @Test
    void checkoutConditionallyReservesStockAndMarksOrderReserved() {
        Product updated = productWithStock(3);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class))).thenReturn(updated);

        Order result = service.createOrders(CUSTOMER_ID, validRequest(), "checkout-key");

        assertEquals("order-id", result.getId());
        assertEquals(2, result.getSchemaVersion());
        assertEquals(1, result.getVersion());
        assertTrue(result.getCheckoutGroupId().startsWith("CHK-"));
        assertEquals(CUSTOMER_ID, result.getCustomerId());
        assertEquals(1, result.getItems().size());
        assertEquals(1, result.getSellerFulfillments().size());
        assertEquals(result.getSellerFulfillments().getFirst().getId(),
                result.getItems().getFirst().getSellerFulfillmentId());
        assertEquals("9876543210", result.getCustomerSnapshot().getPhoneNumber());
        assertNotNull(result.getFinancialSummary());
        assertNotNull(result.getPayment());
        assertEquals("ORDER_CREATED", result.getStatusHistory().getFirst().getStatus());
        assertTrue(result.getStockReserved());
        assertEquals(new BigDecimal("20.00"), result.getFinancialSummary().getAdvanceAmountDue());
        org.mockito.ArgumentCaptor<Query> stockQuery = org.mockito.ArgumentCaptor.forClass(Query.class);
        org.mockito.ArgumentCaptor<Update> stockUpdate = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(stockQuery.capture(), stockUpdate.capture(),
                any(FindAndModifyOptions.class), eq(Product.class));
        Document variantMatch = stockQuery.getValue().getQueryObject().get("variants", Document.class)
                .get("$elemMatch", Document.class);
        assertEquals(VARIANT_ID, variantMatch.getString("_id"));
        assertEquals(2, variantMatch.get("stock", Document.class).getInteger("$gte"));
        assertEquals(-2, stockUpdate.getValue().getUpdateObject()
                .get("$inc", Document.class).get("variants.$.stock"));
        org.mockito.ArgumentCaptor<Query> cartDeleteQuery = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findAndRemove(cartDeleteQuery.capture(), eq(Cart.class));
        assertEquals("cart-id", cartDeleteQuery.getValue().getQueryObject().getString("_id"));
        assertTrue(cartDeleteQuery.getValue().getQueryObject().containsKey("version"));
    }

    @Test
    void onlinePaymentAmountSupportsPrepaidAndConfiguredPartialPercentage() {
        PlaceOrderRequest partial = validRequest();
        assertEquals(new BigDecimal("20.00"), service.calculateOnlinePaymentAmount(CUSTOMER_ID, partial));

        PlaceOrderRequest prepaid = validRequest();
        prepaid.setPaymentMethod(OrderPaymentMethod.PREPAID);
        prepaid.setAdvancePercentage(null);
        assertEquals(new BigDecimal("200.00"), service.calculateOnlinePaymentAmount(CUSTOMER_ID, prepaid));

        ProductVariant variant = product.getVariants().getFirst();
        variant.setMinimumPartialPaymentPercentage(25.0);
        PlaceOrderRequest belowMinimum = validRequest();
        AppException exception = assertThrows(AppException.class,
                () -> service.calculateOnlinePaymentAmount(CUSTOMER_ID, belowMinimum));
        assertTrue(exception.getMessage().contains("at least 25%"));
    }

    @Test
    void codCheckoutCreatesOrderWithoutGatewayAdvanceAndIncludesCodFee() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class))).thenReturn(productWithStock(3));
        PlaceOrderRequest cod = validRequest();
        cod.setPaymentMethod(OrderPaymentMethod.COD);
        cod.setAdvancePercentage(null);
        product.getVariants().getFirst().setPaymentMethod(PaymentMethod.BOTH);

        Order result = service.createOrders(CUSTOMER_ID, cod, "checkout-key");

        assertEquals("COD", result.getPayment().getPaymentType());
        assertEquals("AWAITING_COD_COLLECTION", result.getPayment().getStatus());
        assertEquals("AWAITING_SELLER_RESPONSE", result.getOverallStatus());
        assertEquals(new BigDecimal("0.00"), result.getPayment().getAdvanceAmountDue());
        assertEquals(new BigDecimal("260.00"), result.getPayment().getCodDue());
        assertEquals(new BigDecimal("60.00"), result.getFinancialSummary().getCodCharges());
        assertEquals(new BigDecimal("260.00"), result.getItems().getFirst().getCodDueAtCheckout());
        assertEquals("AWAITING_SELLER_RESPONSE", result.getSellerFulfillments().getFirst().getStatus());
    }

    @Test
    void prepaidCaptureRequiresAndRecordsTheFullOrderAmount() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class))).thenReturn(productWithStock(3));
        PlaceOrderRequest prepaid = validRequest();
        prepaid.setPaymentMethod(OrderPaymentMethod.PREPAID);
        prepaid.setAdvancePercentage(null);
        Order created = service.createOrders(CUSTOMER_ID, prepaid, "checkout-key");
        when(orders.findById("order-id")).thenReturn(Optional.of(created));
        OrderLifecycleServiceImpl lifecycle = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService);

        Order paid = lifecycle.recordAdvancePaymentCaptured("order-id", "razorpay-payment", new BigDecimal("200.00"));

        assertEquals("PAID", paid.getPayment().getStatus());
        assertEquals(0, paid.getPayment().getPaidPercentage().compareTo(new BigDecimal("100.00")));
        assertEquals(0, paid.getPayment().getCodDue().compareTo(BigDecimal.ZERO));
        assertEquals("AWAITING_SELLER_RESPONSE", paid.getOverallStatus());
        assertEquals("PREPAID_CAPTURE", paid.getPayment().getTransactions().getFirst().getType());
    }

    @Test
    void checkoutRejectsWhenAtomicStockReservationFindsInsufficientStock() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class))).thenReturn(null);

        AppException exception = assertThrows(AppException.class,
                () -> service.createOrders(CUSTOMER_ID, validRequest(), "checkout-key"));
        assertTrue(exception.getMessage().contains("Insufficient stock"));

        verify(carts, never()).deleteById(anyString());
        verify(orders).save(any(Order.class));
        verify(mongoTemplate, never()).findAndRemove(any(Query.class), eq(Cart.class));
    }

    @Test
    void retryWithSameIdempotencyKeyReturnsOriginalWithoutReservingOrDeletingAgain() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class))).thenReturn(productWithStock(3));
        Order original = service.createOrders(CUSTOMER_ID, validRequest(), "checkout-key");
        when(orders.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, "checkout-key"))
                .thenReturn(Optional.of(original));

        Order replay = service.createOrders(CUSTOMER_ID, validRequest(), "checkout-key");

        assertSame(original, replay);
        verify(orders, times(2)).save(any(Order.class));
        verify(mongoTemplate, times(1)).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class));
        verify(mongoTemplate, times(1)).findAndRemove(any(Query.class), eq(Cart.class));
    }

    @Test
    void checkoutCreatesOneOrderWithItemsLinkedToTheirSellerFulfillmentsAndImmutableSnapshots() {
        User firstSeller = User.newSeller("9876501234", "seller@example.com");
        firstSeller.setId(SELLER_ID);
        firstSeller.setShopName("Test Seller");
        User secondSeller = User.newSeller("9123405678", "second@example.com");
        secondSeller.setId("6ae2d682477dabe731e2c1f3");
        secondSeller.setShopName("Second Seller");
        when(users.findAllById(any())).thenReturn(List.of(firstSeller, secondSeller));
        when(orders.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, "multi-seller-key"))
                .thenReturn(Optional.empty());

        ProductVariant firstVariant = product.getVariants().getFirst();
        firstVariant.setLabel("1 Litre Bottle");
        firstVariant.setUnit("l");
        firstVariant.setPackSize(1);
        firstVariant.setPackSizeUnit("l");
        firstVariant.setPackQuantity(1);
        firstVariant.setPrintedMrp(100.0);
        firstVariant.setDisplayRate(80.0);
        firstVariant.setDiscountPercentage(99.0); // Checkout snapshots derive from the actual prices.

        ProductVariant secondVariant = new ProductVariant();
        secondVariant.setId("f50721088ade35fc47e0a4dc");
        secondVariant.setSku("S0002-P000002-V01");
        secondVariant.setLabel("500 Gram Pack");
        secondVariant.setUnit("g");
        secondVariant.setPackSize(500);
        secondVariant.setPackSizeUnit("g");
        secondVariant.setPackQuantity(1);
        secondVariant.setStock(4);
        secondVariant.setPrintedMrp(50.0);
        secondVariant.setDisplayRate(45.0);
        secondVariant.setIsPartialPaymentAllowed(true);
        Product secondProduct = new Product();
        secondProduct.setId("6ad871a11df71c7f599dd933");
        secondProduct.setSellerId(secondSeller.getId());
        secondProduct.setTitle("Second Product");
        secondProduct.setProductCode("PRD-SECOND");
        secondProduct.setSku("S0002-P000002");
        secondProduct.setStatus("ACTIVE");
        secondProduct.setIsAvailable(true);
        secondProduct.setIsDeleted(false);
        secondProduct.setVariants(List.of(secondVariant));
        when(variantLookup.findProductsContainingVariants(List.of(VARIANT_ID, secondVariant.getId())))
                .thenReturn(List.of(product, secondProduct));
        cart.setItems(List.of(new CartItem(PRODUCT_ID, VARIANT_ID, 2, 100.0),
                new CartItem(secondProduct.getId(), secondVariant.getId(), 1, 50.0)));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class)))
                .thenReturn(productWithStock(3), secondProductWithStock(secondVariant.getId(), 3));

        org.mockito.ArgumentCaptor<Order> savedOrders = org.mockito.ArgumentCaptor.forClass(Order.class);
        Order order = service.createOrders(CUSTOMER_ID, validRequest(), "multi-seller-key");

        verify(orders, times(2)).save(savedOrders.capture());
        assertSame(savedOrders.getAllValues().getFirst(), savedOrders.getAllValues().getLast());
        assertEquals(2, order.getItems().size());
        assertEquals(2, order.getSellerFulfillments().size());
        assertTrue(order.getCheckoutGroupId().startsWith("CHK-"));
        for (var item : order.getItems()) {
            assertTrue(order.getSellerFulfillments().stream().anyMatch(group ->
                    group.getId().equals(item.getSellerFulfillmentId())
                            && group.getSellerId().equals(item.getSellerId())
                            && group.getItemOrderIds().contains(item.getItemOrderId())));
        }

        var firstItem = order.getItems().getFirst();
        assertEquals("Test Product", firstItem.getProductSnapshot().getTitle());
        assertEquals(new BigDecimal("40.00"), firstItem.getDiscountAmount());
        assertEquals(new BigDecimal("20.00"), firstItem.getDiscountPercentage());
        assertEquals("1 Litre Bottle", firstItem.getVariantSnapshot().getLabel());
        assertEquals(1, firstItem.getVariantSnapshot().getPackSize());
        assertEquals(new BigDecimal("5.00"), order.getItems().getLast().getDiscountAmount());
        OrderResponse response = OrderResponse.forCustomer(order);
        assertEquals("1 Litre Bottle", response.items().getFirst().variantSnapshot().getLabel());

        Document persistedShape = toDocument(order);
        assertEquals(2, persistedShape.getInteger("schemaVersion"));
        assertEquals(order.getCheckoutGroupId(), persistedShape.getString("checkoutGroupId"));
        assertTrue(persistedShape.containsKey("customerSnapshot"));
        assertTrue(persistedShape.containsKey("financialSummary"));
        assertTrue(persistedShape.containsKey("payment"));
        assertTrue(persistedShape.containsKey("statusHistory"));
        assertTrue(persistedShape.containsKey("version"));
        Document persistedItem = persistedShape.getList("items", Document.class).getFirst();
        assertEquals(firstItem.getItemOrderId(), persistedItem.getString("itemOrderId"));
        assertEquals(firstItem.getSellerFulfillmentId(), persistedItem.getString("sellerFulfillmentId"));
        assertEquals("1 Litre Bottle", persistedItem.get("variantSnapshot", Document.class).getString("label"));
        assertTrue(persistedItem.containsKey("productSnapshot"));
        Document persistedGroup = persistedShape.getList("sellerFulfillments", Document.class).getFirst();
        assertTrue(persistedGroup.containsKey("sellerSnapshot"));
        assertTrue(persistedGroup.containsKey("itemOrderIds"));

        product.setTitle("Edited catalog title");
        firstVariant.setLabel("Edited catalog variant");
        firstVariant.setDisplayRate(70.0);
        assertEquals("Test Product", firstItem.getProductSnapshot().getTitle());
        assertEquals("1 Litre Bottle", firstItem.getVariantSnapshot().getLabel());
        assertEquals(new BigDecimal("80.00"), firstItem.getUnitPrice());
    }

    @Test
    void checkoutDoesNotDeleteCartChangedSinceItWasLoaded() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class))).thenReturn(productWithStock(3));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(Cart.class))).thenReturn(null);

        AppException exception = assertThrows(AppException.class,
                () -> service.createOrders(CUSTOMER_ID, validRequest(), "checkout-key"));

        assertTrue(exception.getMessage().contains("Cart changed during checkout"));
        verify(mongoTemplate).findAndRemove(any(Query.class), eq(Cart.class));
    }

    @Test
    void cancellationReleasesReservedStockExactlyOnce() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class)))
                .thenReturn(productWithStock(3), productWithStock(5));
        Order created = service.createOrders(CUSTOMER_ID, validRequest(), "checkout-key");
        when(orders.findByIdAndCustomerId("order-id", CUSTOMER_ID)).thenReturn(Optional.of(created));
        OrderLifecycleServiceImpl lifecycle = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService);

        Order cancelled = lifecycle.cancelForCustomer(CUSTOMER_ID, "order-id");

        assertFalse(cancelled.getStockReserved());
        assertEquals("CANCELLED", cancelled.getOverallStatus());
        assertThrows(AppException.class, () -> lifecycle.cancelForCustomer(CUSTOMER_ID, "order-id"));
        verify(mongoTemplate, times(2)).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class)); // one reserve and one release
    }

    @Test
    void verifiedAdvanceCaptureStartsSellerResponseWindows() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class))).thenReturn(productWithStock(3));
        Order created = service.createOrders(CUSTOMER_ID, validRequest(), "checkout-key");
        when(orders.findById("order-id")).thenReturn(Optional.of(created));

        OrderLifecycleServiceImpl lifecycle = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService);
        Order paid = lifecycle.recordAdvancePaymentCaptured("order-id", "provider-ref-123", new BigDecimal("20.00"));
        Order replay = lifecycle.recordAdvancePaymentCaptured("order-id", "provider-ref-123", new BigDecimal("20.00"));

        assertEquals("AWAITING_SELLER_RESPONSE", paid.getOverallStatus());
        assertSame(paid, replay);
        assertEquals("PARTIALLY_PAID", paid.getPayment().getStatus());
        assertEquals(new BigDecimal("20.00"), paid.getPayment().getAmountPaid());
        assertEquals("AWAITING_SELLER_RESPONSE", paid.getSellerFulfillments().getFirst().getStatus());
        assertNotNull(paid.getSellerFulfillments().getFirst().getResponseDeadlineAt());
        assertTrue(paid.getSellerFulfillments().getFirst().getResponseDeadlineAt().isAfter(LocalDateTime.now().plusHours(11)));
        assertEquals("AWAITING_SELLER_RESPONSE", paid.getItems().getFirst().getItemStatus());
        assertEquals(1, paid.getPayment().getTransactions().size());
    }

    @Test
    void verifiedAdvanceCaptureRejectsAnIncorrectAmount() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class))).thenReturn(productWithStock(3));
        Order created = service.createOrders(CUSTOMER_ID, validRequest(), "checkout-key");
        when(orders.findById("order-id")).thenReturn(Optional.of(created));
        OrderLifecycleServiceImpl lifecycle = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService);

        assertThrows(AppException.class, () -> lifecycle.recordAdvancePaymentCaptured(
                "order-id", "provider-ref-123", new BigDecimal("19.99")));
        verify(orders, never()).save(argThat(order -> "AWAITING_SELLER_RESPONSE".equals(order.getOverallStatus())));
    }

    @Test
    void sellerAcceptanceStartsFortyEightHourDispatchWindow() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class))).thenReturn(productWithStock(3));
        Order created = service.createOrders(CUSTOMER_ID, validRequest(), "checkout-key");
        when(orders.findById("order-id")).thenReturn(Optional.of(created));
        OrderLifecycleServiceImpl lifecycle = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService);
        lifecycle.recordAdvancePaymentCaptured("order-id", "provider-ref-accept", new BigDecimal("20.00"));
        when(orders.findByIdAndSellerFulfillmentsSellerId("order-id", SELLER_ID)).thenReturn(Optional.of(created));

        Order accepted = lifecycle.respondToSellerFulfillment(SELLER_ID, "order-id",
                created.getSellerFulfillments().getFirst().getId(), "ACCEPT");

        Order.SellerFulfillment fulfillment = accepted.getSellerFulfillments().getFirst();
        assertEquals("PROCESSING", accepted.getOverallStatus());
        assertEquals("ACCEPTED", fulfillment.getStatus());
        assertNotNull(fulfillment.getAcceptedAt());
        assertTrue(fulfillment.getDispatchSlaDeadlineAt().isAfter(LocalDateTime.now().plusHours(47)));
    }

    @Test
    void sellerRejectionOpensSixHourCustomerDecisionWindow() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class))).thenReturn(productWithStock(3));
        Order created = service.createOrders(CUSTOMER_ID, validRequest(), "checkout-key");
        when(orders.findById("order-id")).thenReturn(Optional.of(created));
        OrderLifecycleServiceImpl lifecycle = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService);
        lifecycle.recordAdvancePaymentCaptured("order-id", "provider-ref-reject", new BigDecimal("20.00"));
        when(orders.findByIdAndSellerFulfillmentsSellerId("order-id", SELLER_ID)).thenReturn(Optional.of(created));

        Order rejected = lifecycle.respondToSellerFulfillment(SELLER_ID, "order-id",
                created.getSellerFulfillments().getFirst().getId(), "REJECT");

        assertEquals("CUSTOMER_DECISION_REQUIRED", rejected.getOverallStatus());
        assertEquals("REJECTED", rejected.getSellerFulfillments().getFirst().getStatus());
        assertEquals("CANCELLED_BY_SELLER", rejected.getItems().getFirst().getItemStatus());
        assertEquals(1, rejected.getCustomerDecisionWindows().size());
        assertEquals("OPEN", rejected.getCustomerDecisionWindows().getFirst().getStatus());
        assertTrue(rejected.getCustomerDecisionWindows().getFirst().getDeadlineAt().isAfter(LocalDateTime.now().plusHours(5)));
    }

    @Test
    void customerCanContinueAfterSellerRejectionAndRefundIsRecordedAsPending() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class))).thenReturn(productWithStock(3));
        Order created = service.createOrders(CUSTOMER_ID, validRequest(), "checkout-key");
        when(orders.findById("order-id")).thenReturn(Optional.of(created));
        OrderLifecycleServiceImpl lifecycle = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService);
        lifecycle.recordAdvancePaymentCaptured("order-id", "provider-ref-window", new BigDecimal("20.00"));
        when(orders.findByIdAndSellerFulfillmentsSellerId("order-id", SELLER_ID)).thenReturn(Optional.of(created));
        Order rejected = lifecycle.respondToSellerFulfillment(SELLER_ID, "order-id",
                created.getSellerFulfillments().getFirst().getId(), "REJECT");
        when(orders.findByIdAndCustomerId("order-id", CUSTOMER_ID)).thenReturn(Optional.of(rejected));
        String windowId = rejected.getCustomerDecisionWindows().getFirst().getDecisionWindowId();

        Order continued = lifecycle.resolveCustomerDecisionWindow(CUSTOMER_ID, "order-id", windowId, "CONTINUE", null);

        assertEquals("CANCELLED", continued.getOverallStatus());
        assertEquals("RESOLVED", continued.getCustomerDecisionWindows().getFirst().getStatus());
        assertEquals("CONTINUE", continued.getCustomerDecisionWindows().getFirst().getResolution());
        assertEquals(new BigDecimal("0.00"), continued.getFinancialSummary().getCurrentOrderTotal());
        assertEquals(new BigDecimal("0.00"), continued.getFinancialSummary().getCodDue());
        assertEquals("PENDING_PROVIDER_INTEGRATION", continued.getPayment().getRefunds().getFirst().getStatus());
        assertEquals(new BigDecimal("20.00"), continued.getPayment().getRefunds().getFirst().getAmount());
    }

    @Test
    void sellerResponseTimeoutCancelsItemsAndOpensCustomerWindow() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class))).thenReturn(productWithStock(3));
        Order created = service.createOrders(CUSTOMER_ID, validRequest(), "checkout-key");
        when(orders.findById("order-id")).thenReturn(Optional.of(created));
        OrderLifecycleServiceImpl lifecycle = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService);
        lifecycle.recordAdvancePaymentCaptured("order-id", "provider-ref-timeout", new BigDecimal("20.00"));
        created.getSellerFulfillments().getFirst().setResponseDeadlineAt(LocalDateTime.now().minusSeconds(1));
        when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of(created));

        lifecycle.processExpiredSellerResponseWindows();

        assertEquals("EXPIRED_TIMEOUT", created.getSellerFulfillments().getFirst().getStatus());
        assertEquals("CANCELLED_BY_SYSTEM", created.getItems().getFirst().getItemStatus());
        assertEquals("CUSTOMER_DECISION_REQUIRED", created.getOverallStatus());
        assertEquals("OPEN", created.getCustomerDecisionWindows().getFirst().getStatus());
        assertEquals("PENDING_PROVIDER_INTEGRATION", created.getPayment().getRefunds().getFirst().getStatus());
    }

    @Test
    void expiredCustomerDecisionWindowDefaultsToContinue() {
        Order created = new Order();
        created.setId("order-id");
        Order.SellerFulfillment fulfillment = new Order.SellerFulfillment();
        fulfillment.setId("fulfillment-id");
        fulfillment.setSellerId(SELLER_ID);
        fulfillment.setStatus("REJECTED");
        created.setSellerFulfillments(List.of(fulfillment));
        Order.CustomerDecisionWindow window = new Order.CustomerDecisionWindow();
        window.setDecisionWindowId("window-id");
        window.setStatus("OPEN");
        window.setDeadlineAt(LocalDateTime.now().minusSeconds(1));
        created.setCustomerDecisionWindows(List.of(window));
        created.setItems(List.of());
        when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of(created));
        OrderLifecycleServiceImpl lifecycle = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService);

        lifecycle.continueOrdersAfterExpiredCustomerDecisionWindows();

        assertEquals("RESOLVED", window.getStatus());
        assertEquals("CONTINUE", window.getResolution());
        assertEquals("CANCELLED", created.getOverallStatus());
    }

    @Test
    void packedFulfillmentWithoutCarrierHandoverIsFlaggedForAdminReviewAtSlaDeadline() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Product.class))).thenReturn(productWithStock(3));
        Order created = service.createOrders(CUSTOMER_ID, validRequest(), "checkout-key");
        when(orders.findById("order-id")).thenReturn(Optional.of(created));
        when(orders.findByIdAndSellerFulfillmentsSellerId("order-id", SELLER_ID)).thenReturn(Optional.of(created));
        OrderLifecycleServiceImpl lifecycle = new OrderLifecycleServiceImpl(orders, mongoTemplate, redisCache, webSocketService);
        lifecycle.recordAdvancePaymentCaptured("order-id", "provider-ref-sla", new BigDecimal("20.00"));
        String fulfillmentId = created.getSellerFulfillments().getFirst().getId();
        lifecycle.respondToSellerFulfillment(SELLER_ID, "order-id", fulfillmentId, "ACCEPT");
        Order packed = lifecycle.markSellerFulfillmentPacked(SELLER_ID, "order-id", fulfillmentId);
        assertEquals("PACKED", packed.getSellerFulfillments().getFirst().getStatus());

        packed.getSellerFulfillments().getFirst().setDispatchSlaDeadlineAt(LocalDateTime.now().minusSeconds(1));
        when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of(packed));
        lifecycle.flagExpiredDispatchSlaForAdminReview();

        assertEquals("IN_DELAY_REVIEW", packed.getSellerFulfillments().getFirst().getStatus());
        assertEquals("OPEN", packed.getSellerFulfillments().getFirst().getDelayReview().getStatus());
    }

    private Product productWithStock(int stock) {
        Product updated = new Product();
        updated.setId(PRODUCT_ID);
        ProductVariant variant = new ProductVariant();
        variant.setId(VARIANT_ID);
        variant.setStock(stock);
        updated.setVariants(List.of(variant));
        return updated;
    }

    private Product secondProductWithStock(String variantId, int stock) {
        Product updated = new Product();
        updated.setId("6ad871a11df71c7f599dd933");
        ProductVariant variant = new ProductVariant();
        variant.setId(variantId);
        variant.setStock(stock);
        updated.setVariants(List.of(variant));
        return updated;
    }

    private Document toDocument(Order order) {
        MongoCustomConversions conversions = MongoCustomConversions.create(adapter -> { });
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        Document document = new Document();
        converter.write(order, document);
        return document;
    }

    private PlaceOrderRequest validRequest() {
        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setPaymentMethod(OrderPaymentMethod.PARTIAL);
        request.setAdvancePercentage(new BigDecimal("10"));
        PlaceOrderRequest.AddressInput address = new PlaceOrderRequest.AddressInput();
        address.setName("Test Customer");
        address.setPhoneNumber("9876543210");
        address.setVillageArea("Test Area");
        address.setCityTehsil("Indore");
        address.setState("Madhya Pradesh");
        address.setPincode("452001");
        request.setShippingAddress(address);
        return request;
    }
}
