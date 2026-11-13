package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.PlaceOrderRequest;
import com.ebs.biocrop.entity.*;
import com.ebs.biocrop.entity.enums.OrderStatus;
import com.ebs.biocrop.entity.enums.PaymentMethod;
import com.ebs.biocrop.entity.enums.PaymentStatus;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CartRepository;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.repository.ProductVariantLookup;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.OrderPlacementService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.websocket.WebSocketService;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** Creates one complete order document per checkout, regardless of seller count. */
@Service
public class OrderPlacementServiceImpl implements OrderPlacementService {
    private static final BigDecimal ADVANCE_RATE = new BigDecimal("10.00");
    private final CartRepository carts;
    private final ProductVariantLookup variantLookup;
    private final UserRepository users;
    private final OrderRepository orders;
    private final MongoTemplate mongoTemplate;
    private final RedisJsonCache redisCache;
    private final WebSocketService webSocketService;

    public OrderPlacementServiceImpl(CartRepository carts, ProductVariantLookup variantLookup,
                                     UserRepository users, OrderRepository orders,
                                     MongoTemplate mongoTemplate, RedisJsonCache redisCache,
                                     WebSocketService webSocketService) {
        this.carts = carts;
        this.variantLookup = variantLookup;
        this.users = users;
        this.orders = orders;
        this.mongoTemplate = mongoTemplate;
        this.redisCache = redisCache;
        this.webSocketService = webSocketService;
    }

    @Override
    @Transactional
    public Order createOrders(String customerId, PlaceOrderRequest request, String idempotencyKey) {
        return createOrder(customerId, request, idempotencyKey, false, customerId);
    }

    @Override
    @Transactional
    public Order createOrdersForAdmin(String customerId, PlaceOrderRequest request, String adminId, String idempotencyKey) {
        return createOrder(customerId, request, idempotencyKey, true, adminId);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculateOnlinePaymentAmount(String customerId, PlaceOrderRequest request) {
        if (request == null || request.getPaymentMethod() == null || request.getPaymentMethod() == PaymentMethod.COD) {
            throw new AppException("Select PREPAID or PARTIAL for online payment", HttpStatus.BAD_REQUEST);
        }
        Cart cart = carts.findByUser(customerId)
                .orElseThrow(() -> new AppException("Cart is empty", HttpStatus.BAD_REQUEST));
        if (cart.getItems() == null || cart.getItems().isEmpty()) throw new AppException("Cart is empty", HttpStatus.BAD_REQUEST);
        List<String> variantIds = cart.getItems().stream().map(CartItem::getVariantId).filter(Objects::nonNull).toList();
        Set<String> wanted = variantIds.stream().map(this::normalizeId).collect(Collectors.toSet());
        Map<String, ProductVariant> variants = new HashMap<>();
        Map<String, Product> products = new HashMap<>();
        for (Product product : variantLookup.findProductsContainingVariants(variantIds)) {
            for (ProductVariant variant : product.getVariants() == null ? List.<ProductVariant>of() : product.getVariants()) {
                if (variant.getId() != null && wanted.contains(normalizeId(variant.getId()))) {
                    String id = normalizeId(variant.getId());
                    if (variants.putIfAbsent(id, variant) != null) throw new AppException("A product variant is linked to more than one product", HttpStatus.CONFLICT);
                    products.put(id, product);
                }
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal minimumRequired = ADVANCE_RATE;
        BigDecimal percentage = request.getPaymentMethod() == PaymentMethod.PREPAID
                ? BigDecimal.valueOf(100) : request.getAdvancePercentage() == null ? ADVANCE_RATE : request.getAdvancePercentage();
        for (CartItem item : cart.getItems()) {
            ProductVariant variant = variants.get(normalizeId(item.getVariantId()));
            Product product = products.get(normalizeId(item.getVariantId()));
            if (variant == null || product == null) throw new AppException("A cart item is no longer available", HttpStatus.CONFLICT);
            validatePaymentOption(variant, request.getPaymentMethod(), percentage, product.getTitle());
            if (request.getPaymentMethod() == PaymentMethod.PARTIAL) {
                BigDecimal min = variant.getMinimumPartialPaymentPercentage() == null ? ADVANCE_RATE
                        : BigDecimal.valueOf(variant.getMinimumPartialPaymentPercentage());
                minimumRequired = minimumRequired.max(min);
            }
            total = total.add(price(variant.getEffectivePrice(), product.getTitle()).multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        if (request.getPaymentMethod() == PaymentMethod.PARTIAL
                && (percentage.compareTo(minimumRequired) < 0 || percentage.compareTo(BigDecimal.valueOf(99)) > 0)) {
            throw new AppException("Partial payment percentage must meet every item's minimum (" + minimumRequired.stripTrailingZeros().toPlainString() + "%) and be below 100%", HttpStatus.BAD_REQUEST);
        }
        return total.multiply(percentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private Order createOrder(String customerId, PlaceOrderRequest request, String idempotencyKey,
                              boolean adminOverride, String actorId) {
        if (request == null || request.getShippingAddress() == null) {
            throw new AppException("Shipping address is required", HttpStatus.BAD_REQUEST);
        }
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (key.isBlank() || key.length() > 128) {
            throw new AppException("A valid Idempotency-Key header is required", HttpStatus.BAD_REQUEST);
        }
        Optional<Order> retry = orders.findByCustomerIdAndIdempotencyKey(customerId, key);
        if (retry.isPresent()) return retry.get();
        if (request.getPaymentMethod() == null) {
            throw new AppException("A checkout payment method is required", HttpStatus.BAD_REQUEST);
        }
        BigDecimal paymentPercentage = request.getPaymentMethod() == PaymentMethod.PREPAID ? BigDecimal.valueOf(100)
                : request.getPaymentMethod() == PaymentMethod.COD ? BigDecimal.ZERO
                : request.getAdvancePercentage() == null ? ADVANCE_RATE : request.getAdvancePercentage();

        User customer = users.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", customerId));
        if (customer.getRole() != UserRole.ROLE_CUSTOMER || Boolean.TRUE.equals(customer.getIsDeleted())
                || Boolean.TRUE.equals(customer.getIsBlocked()) || !"ACTIVE".equalsIgnoreCase(customer.getStatus())) {
            throw new AppException("An active customer account is required to place an order", HttpStatus.CONFLICT);
        }
        Cart cart = carts.findByUser(customerId)
                .orElseThrow(() -> new AppException("Cart is empty", HttpStatus.BAD_REQUEST));
        if (cart.getItems() == null || cart.getItems().isEmpty()) throw new AppException("Cart is empty", HttpStatus.BAD_REQUEST);
        if (cart.getAppliedCoupon() != null && !cart.getAppliedCoupon().isBlank()) {
            throw new AppException("Coupon checkout is not available yet; remove the coupon and try again", HttpStatus.CONFLICT);
        }
        if (cart.getFreeItems() != null && !cart.getFreeItems().isEmpty()) {
            throw new AppException("Promotional free items are not supported in checkout yet", HttpStatus.CONFLICT);
        }

        List<String> variantIds = cart.getItems().stream().map(CartItem::getVariantId)
                .filter(Objects::nonNull).toList();
        Set<String> requestedVariants = variantIds.stream().map(this::normalizeId).collect(Collectors.toSet());
        Map<String, Product> productsByVariant = new HashMap<>();
        for (Product product : variantLookup.findProductsContainingVariants(variantIds)) {
            if (product.getVariants() == null) continue;
            for (ProductVariant variant : product.getVariants()) {
                if (variant.getId() == null) continue;
                String variantKey = normalizeId(variant.getId());
                if (requestedVariants.contains(variantKey) && productsByVariant.putIfAbsent(variantKey, product) != null) {
                    throw new AppException("A product variant is linked to more than one product", HttpStatus.CONFLICT);
                }
            }
        }

        List<CartLine> lines = new ArrayList<>();
        Set<String> sellerIds = new LinkedHashSet<>();
        for (CartItem cartItem : cart.getItems()) {
            if (cartItem.getQuantity() == null || cartItem.getQuantity() <= 0 || cartItem.getVariantId() == null) {
                throw new AppException("Cart contains an invalid item", HttpStatus.CONFLICT);
            }
            Product product = productsByVariant.get(normalizeId(cartItem.getVariantId()));
            ProductVariant variant = product == null || product.getVariants() == null ? null
                    : product.getVariants().stream().filter(v -> v.getId() != null
                    && normalizeId(v.getId()).equals(normalizeId(cartItem.getVariantId()))).findFirst().orElse(null);
            if (product == null || variant == null) throw new ResourceNotFoundException("Product variant", "id", cartItem.getVariantId());
            if (!"ACTIVE".equalsIgnoreCase(product.getStatus()) || !Boolean.TRUE.equals(product.getIsAvailable())
                    || Boolean.TRUE.equals(product.getIsDeleted()) || product.getSellerId() == null) {
                throw new AppException("A cart product is no longer available", HttpStatus.CONFLICT);
            }
            if (variant.getStock() == null || variant.getStock() < cartItem.getQuantity()) {
                throw new AppException("Insufficient stock for product '" + product.getTitle() + "'", HttpStatus.CONFLICT);
            }
            validatePaymentOption(variant, request.getPaymentMethod(), paymentPercentage, product.getTitle());
            BigDecimal unitPrice = price(variant.getEffectivePrice(), product.getTitle());
            BigDecimal listPrice = variant.getPrintedMrp() == null ? unitPrice : price(variant.getPrintedMrp(), product.getTitle());
            BigDecimal quantity = BigDecimal.valueOf(cartItem.getQuantity());
            BigDecimal lineTotal = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            BigDecimal listTotal = listPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            lines.add(new CartLine(product, variant, cartItem.getQuantity(), unitPrice, listPrice,
                    listTotal.subtract(lineTotal).max(BigDecimal.ZERO), lineTotal));
            sellerIds.add(product.getSellerId());
        }

        Map<String, User> sellers = StreamSupport.stream(users.findAllById(sellerIds).spliterator(), false)
                .collect(Collectors.toMap(User::getId, u -> u));
        if (sellers.size() != sellerIds.size() || sellers.values().stream().anyMatch(seller ->
                seller.getRole() != UserRole.ROLE_SELLER || Boolean.TRUE.equals(seller.getIsBlocked())
                        || Boolean.TRUE.equals(seller.getIsDeleted()) || !"ACTIVE".equalsIgnoreCase(seller.getStatus()))) {
            throw new AppException("A seller for a cart product is unavailable", HttpStatus.CONFLICT);
        }

        ObjectId generatedId = new ObjectId();
        String suffix = generatedId.toHexString().toUpperCase(Locale.ROOT);
        String checkoutGroupId = "CHK-" + suffix;
        String orderNumber = "ORD-" + suffix;
        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        // Do NOT pre-assign the ID — let Spring Data MongoDB perform a true insert.
        // Pre-assigning @Id with @Version causes Spring to issue an update-with-version-check
        // instead of an insert, which throws OptimisticLockingFailureException on first save.
        order.setOrderNumber(orderNumber);
        order.setCheckoutGroupId(checkoutGroupId);
        order.setIdempotencyKey(key);
        order.setCustomerId(customerId);
        order.setCustomerSnapshot(customerSnapshot(customer));
        OrderAddressSnapshot shipping = snapshot(request.getShippingAddress());
        OrderAddressSnapshot billing = request.getBillingAddress() == null ? copyAddress(shipping) : snapshot(request.getBillingAddress());
        order.setShippingAddress(shipping);
        order.setBillingAddress(billing);

        Map<String, Order.SellerFulfillment> groups = new LinkedHashMap<>();
        BigDecimal totalList = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal orderTotal = BigDecimal.ZERO;
        BigDecimal totalCodCharges = BigDecimal.ZERO;
        List<OrderItem> items = new ArrayList<>();
        int itemSequence = 1;
        for (CartLine line : lines) {
            String sellerId = line.product().getSellerId();
            Order.SellerFulfillment group = groups.computeIfAbsent(sellerId, id -> fulfillment(sellers.get(id), now));
            OrderItem item = toOrderItem(line, sellerId, group.getId(), orderNumber + "-" + String.format(Locale.ROOT, "%03d", itemSequenceSafe(itemSequence)));
            itemSequence++;
            group.getItemOrderIds().add(item.getItemOrderId());
            items.add(item);
            totalList = totalList.add(line.listTotal());
            totalDiscount = totalDiscount.add(line.discountTotal());
            orderTotal = orderTotal.add(line.lineTotal());
            if (request.getPaymentMethod() != PaymentMethod.PREPAID && line.variant().getCodCharge() != null) {
                totalCodCharges = totalCodCharges.add(BigDecimal.valueOf(line.variant().getCodCharge())
                        .multiply(BigDecimal.valueOf(line.quantity())));
            }
        }
        orderTotal = orderTotal.setScale(2, RoundingMode.HALF_UP);
        totalCodCharges = totalCodCharges.setScale(2, RoundingMode.HALF_UP);
        BigDecimal advanceTotal = orderTotal.multiply(paymentPercentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            OrderItem item = items.get(i);
            BigDecimal itemAdvance = i == items.size() - 1
                    ? advanceTotal.subtract(allocated)
                    : item.getLineTotal().multiply(paymentPercentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            allocated = allocated.add(itemAdvance);
            item.setAdvanceAllocatedAtCheckout(itemAdvance);
            BigDecimal itemCodCharge = request.getPaymentMethod() == PaymentMethod.PREPAID ? BigDecimal.ZERO
                    : BigDecimal.valueOf(lines.get(i).variant().getCodCharge() == null ? 0.0 : lines.get(i).variant().getCodCharge())
                    .multiply(BigDecimal.valueOf(item.getQuantity()));
            item.setCodDueAtCheckout(item.getLineTotal().subtract(itemAdvance).add(itemCodCharge).setScale(2, RoundingMode.HALF_UP));
        }
        order.setItems(items);
        order.setSellerFulfillments(new ArrayList<>(groups.values()));

        Order.FinancialSummary summary = new Order.FinancialSummary();
        summary.setGrossMerchandiseValue(totalList.setScale(2, RoundingMode.HALF_UP));
        summary.setDiscountTotal(totalDiscount.setScale(2, RoundingMode.HALF_UP));
        summary.setOriginalOrderTotal(orderTotal);
        summary.setAdvanceAmountDue(advanceTotal);
        summary.setOnlineAmountCaptured(BigDecimal.ZERO);
        summary.setNetOnlineAmountRetained(BigDecimal.ZERO);
        summary.setCodCharges(totalCodCharges);
        summary.setCodDue(orderTotal.subtract(advanceTotal).add(totalCodCharges).setScale(2, RoundingMode.HALF_UP));
        summary.setCurrentOrderTotal(orderTotal.add(totalCodCharges).setScale(2, RoundingMode.HALF_UP));
        summary.setCancelledAmount(BigDecimal.ZERO);
        summary.setRefundedTotal(BigDecimal.ZERO);
        // Tax and shipping are explicitly unknown/not calculated in this first order slice.
        summary.setTaxTotal(null);
        summary.setDeliveryCharges(null);
        order.setFinancialSummary(summary);

        OrderPaymentDetails payment = new OrderPaymentDetails();
        payment.setPaymentType(switch (request.getPaymentMethod()) {
            case PREPAID -> "PREPAID";
            case PARTIAL -> "PARTIAL_ADVANCE_COD";
            case COD -> "COD";
            default -> "PREPAID";
        });
        payment.setStatus(PaymentStatus.CREATED.name());
        payment.setAdvancePercentage(paymentPercentage);
        payment.setAdvanceAmountDue(advanceTotal);
        payment.setAdvanceAmountPaid(BigDecimal.ZERO);
        payment.setCodDue(orderTotal.subtract(advanceTotal).add(totalCodCharges));
        payment.setCodCollected(BigDecimal.ZERO);
        payment.setAmountPaid(BigDecimal.ZERO);
        payment.setRemainingAmount(orderTotal.subtract(advanceTotal).add(totalCodCharges));
        payment.setPaidPercentage(BigDecimal.ZERO);
        payment.setRemainingPercentage(BigDecimal.valueOf(100).subtract(paymentPercentage));
        payment.setTransactions(new ArrayList<>());
        payment.setRefunds(new ArrayList<>());
        order.setPayment(payment);
        boolean codOrder = request.getPaymentMethod() == PaymentMethod.COD;
        order.setOverallStatus(codOrder ? OrderStatus.CONFIRMED.name() : OrderStatus.PENDING_PAYMENT.name());
        order.setCustomerDecisionWindows(new ArrayList<>());
        order.setPlacedAt(now);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.setStockReserved(false);
        OrderStatusEvent initial = new OrderStatusEvent();
        initial.setStatus(codOrder ? "ORDER_CREATED_COD" : "ORDER_CREATED");
        initial.setOccurredAt(now);
        initial.setActor(adminOverride ? actorId : "CUSTOMER");
        initial.setNote(codOrder ? "Customer confirmed cash on delivery" : adminOverride ? "Order created by Admin for customer; awaiting online payment" : "Checkout order created after online payment verification");
        order.setStatusHistory(List.of(initial));
        if (codOrder) {
            for (Order.SellerFulfillment fulfillment : order.getSellerFulfillments()) {
                fulfillment.setStatus(OrderStatus.CONFIRMED.name());
                fulfillment.setResponseDeadlineAt(now.plusHours(12));
            }
            for (OrderItem item : order.getItems()) item.setItemStatus(OrderStatus.CONFIRMED.name());
        }

        try {
            Order saved = orders.save(order);
            List<StockUpdate> stockUpdates = reserveStock(lines);
            saved.setStockReserved(true);
            saved = orders.save(saved);
            Cart removedCart = mongoTemplate.findAndRemove(Query.query(Criteria.where("_id").is(cart.getId())
                    .and("version").is(cart.getVersion())), Cart.class);
            if (removedCart == null) {
                throw new AppException("Cart changed during checkout. Refresh the cart and retry.", HttpStatus.CONFLICT);
            }
            redisCache.invalidateRegionAfterCommit("public-products");
            stockUpdates.forEach(update -> webSocketService.broadcast("inventory:stock_update", Map.of(
                    "productId", update.productId(),
                    "variantId", update.variantId(),
                    "stock", update.stock())));
            webSocketService.broadcast("orders:new", Map.of(
                    "orderId", saved.getId(),
                    "orderNumber", saved.getOrderNumber(),
                    "checkoutGroupId", saved.getCheckoutGroupId(),
                    "overallStatus", saved.getOverallStatus()));
            return saved;
        } catch (DuplicateKeyException duplicate) {
            return orders.findByCustomerIdAndIdempotencyKey(customerId, key).orElseThrow(() -> duplicate);
        }
    }

    private List<StockUpdate> reserveStock(List<CartLine> lines) {
        List<StockUpdate> updates = new ArrayList<>();
        for (CartLine line : lines) {
            Query query = Query.query(Criteria.where("_id").is(line.product().getId())
                    .and("status").is("ACTIVE")
                    .and("isAvailable").is(true)
                    .and("isDeleted").ne(true)
                    .and("variants").elemMatch(Criteria.where("_id").is(line.variant().getId())
                            .and("stock").gte(line.quantity())));
            Product updated = mongoTemplate.findAndModify(query,
                    new Update().inc("variants.$.stock", -line.quantity()).inc("version", 1),
                    FindAndModifyOptions.options().returnNew(true), Product.class);
            if (updated == null) {
                throw new AppException("Insufficient stock or product availability changed for '"
                        + line.product().getTitle() + "'. Refresh the cart and retry.", HttpStatus.CONFLICT);
            }
            Integer remainingStock = updated.getVariants().stream()
                    .filter(variant -> Objects.equals(normalizeId(variant.getId()), normalizeId(line.variant().getId())))
                    .map(ProductVariant::getStock)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElseThrow(() -> new AppException("Reserved stock could not be verified for '"
                            + line.product().getTitle() + "'", HttpStatus.CONFLICT));
            updates.add(new StockUpdate(line.product().getId(), line.variant().getId(), remainingStock));
        }
        return updates;
    }

    private Order.SellerFulfillment fulfillment(User seller, LocalDateTime now) {
        Order.SellerFulfillment group = new Order.SellerFulfillment();
        group.setId(new ObjectId().toHexString());
        group.setSellerId(seller.getId());
        OrderSellerSnapshot snapshot = new OrderSellerSnapshot();
        // legalName is intentionally null until the seller onboarding schema supplies a verified value.
        snapshot.setLegalName(null);
        snapshot.setTradeName(seller.getShopName());
        snapshot.setGstNumber(seller.getGstNumber());
        snapshot.setContactPhone(seller.getPhoneNumber());
        snapshot.setDispatchPincode(seller.getAddress() == null ? null : seller.getAddress().getPincode());
        snapshot.setSellerCode(seller.getSellerCode());
        group.setSellerSnapshot(snapshot);
        group.setStatus(OrderStatus.PENDING_PAYMENT.name());
        group.setResponseDeadlineAt(null);
        group.setAcceptedAt(null);
        group.setDispatchSlaDeadlineAt(null);
        group.setDispatchedAt(null);
        group.setShipments(new ArrayList<>());
        group.setDelayReview(null);
        return group;
    }

    private OrderItem toOrderItem(CartLine line, String sellerId, String fulfillmentId, String itemOrderId) {
        OrderItem item = new OrderItem();
        item.setItemOrderId(itemOrderId);
        item.setSellerId(sellerId);
        item.setSellerFulfillmentId(fulfillmentId);
        item.setProductId(line.product().getId());
        item.setCategoryId(line.product().getCategoryId());
        item.setVariantId(line.variant().getId());
        item.setProductCode(line.product().getProductCode());
        item.setProductSku(line.product().getSku());
        item.setVariantSku(line.variant().getSku());
        OrderItem.VariantSnapshot variantSnapshot = new OrderItem.VariantSnapshot();
        variantSnapshot.setLabel(variantLabel(line.variant()));
        variantSnapshot.setUnit(line.variant().getUnit());
        variantSnapshot.setPackSize(line.variant().getPackSize());
        variantSnapshot.setPackSizeUnit(line.variant().getPackSizeUnit());
        variantSnapshot.setPackQuantity(line.variant().getPackQuantity());
        item.setVariantSnapshot(variantSnapshot);
        Order.ProductSnapshot snapshot = new Order.ProductSnapshot();
        snapshot.setTitle(line.product().getTitle());
        snapshot.setVariantLabel(variantLabel(line.variant()));
        snapshot.setHsnCode(line.product().getHsnCode());
        snapshot.setTaxRatePercentage(line.variant().getGstPercentage() == null ? null : BigDecimal.valueOf(line.variant().getGstPercentage()));
        snapshot.setImageObjectName(line.product().getProductImages() == null || line.product().getProductImages().isEmpty()
                ? null : line.product().getProductImages().getFirst().getMidObjectName());
        snapshot.setCategoryName(null);
        item.setProductSnapshot(snapshot);
        item.setQuantity(line.quantity());
        item.setListPrice(line.listPrice());
        item.setDiscountAmount(line.discountTotal());
        item.setDiscountPercentage(line.listPrice().signum() == 0 ? null
                : line.listPrice().subtract(line.unitPrice()).max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(100)).divide(line.listPrice(), 2, RoundingMode.HALF_UP));
        item.setUnitPrice(line.unitPrice());
        item.setTaxAmount(null);
        item.setLineTotal(line.lineTotal());
        item.setItemStatus(OrderStatus.PENDING_PAYMENT.name());
        item.setCancellation(null);
        return item;
    }

    private OrderCustomerSnapshot customerSnapshot(User customer) {
        OrderCustomerSnapshot snapshot = new OrderCustomerSnapshot();
        snapshot.setFirstName(customer.getFirstName());
        snapshot.setLastName(customer.getLastName());
        snapshot.setPhoneNumber(customer.getPhoneNumber());
        snapshot.setEmail(customer.getEmail());
        return snapshot;
    }

    private OrderAddressSnapshot snapshot(PlaceOrderRequest.AddressInput input) {
        OrderAddressSnapshot address = new OrderAddressSnapshot();
        address.setName(input.getName().trim());
        address.setPhoneNumber(input.getPhoneNumber().trim());
        address.setVillageArea(input.getVillageArea().trim());
        address.setAddress2(input.getAddress2() == null ? null : input.getAddress2().trim());
        address.setCityTehsil(input.getCityTehsil().trim());
        address.setDistrict(input.getDistrict() == null ? null : input.getDistrict().trim());
        address.setState(input.getState().trim());
        address.setPincode(input.getPincode());
        address.setCountry(input.getCountry());
        return address;
    }

    private OrderAddressSnapshot copyAddress(OrderAddressSnapshot source) {
        OrderAddressSnapshot copy = new OrderAddressSnapshot();
        copy.setName(source.getName()); copy.setPhoneNumber(source.getPhoneNumber());
        copy.setVillageArea(source.getVillageArea()); copy.setAddress2(source.getAddress2());
        copy.setCityTehsil(source.getCityTehsil()); copy.setDistrict(source.getDistrict()); copy.setState(source.getState());
        copy.setPincode(source.getPincode());
        copy.setCountry(source.getCountry());
        return copy;
    }

    private BigDecimal price(Double value, String title) {
        if (value == null || !Double.isFinite(value) || value < 0) throw new AppException("Product '" + title + "' has an invalid price", HttpStatus.CONFLICT);
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private void validatePaymentOption(ProductVariant variant, PaymentMethod checkoutMethod,
                                       BigDecimal requestedPercentage, String productTitle) {
        PaymentMethod configured = variant.getPaymentMethod() == null ? PaymentMethod.ONLINE : variant.getPaymentMethod();
        boolean onlineSupported = configured == PaymentMethod.ONLINE || configured == PaymentMethod.BOTH;
        boolean codSupported = configured == PaymentMethod.COD || configured == PaymentMethod.BOTH;
        switch (checkoutMethod) {
            case PREPAID -> {
                if (!onlineSupported || !Boolean.TRUE.equals(variant.getIsPrepaidAllowed())) {
                    throw new AppException("Product '" + productTitle + "' does not allow prepaid online payment", HttpStatus.CONFLICT);
                }
            }
            case PARTIAL -> {
                if (!onlineSupported || !Boolean.TRUE.equals(variant.getIsPartialPaymentAllowed())) {
                    throw new AppException("Product '" + productTitle + "' does not allow partial online payment", HttpStatus.CONFLICT);
                }
                BigDecimal minimum = variant.getMinimumPartialPaymentPercentage() == null ? ADVANCE_RATE
                        : BigDecimal.valueOf(variant.getMinimumPartialPaymentPercentage());
                if (requestedPercentage == null || requestedPercentage.compareTo(minimum) < 0
                        || requestedPercentage.compareTo(BigDecimal.valueOf(99)) > 0) {
                    throw new AppException("Partial payment for '" + productTitle + "' must be at least "
                            + minimum.stripTrailingZeros().toPlainString() + "% and less than 100%", HttpStatus.BAD_REQUEST);
                }
            }
            case COD -> {
                if (!codSupported) {
                    throw new AppException("Product '" + productTitle + "' does not allow cash on delivery", HttpStatus.CONFLICT);
                }
            }
        }
    }

    private String variantLabel(ProductVariant variant) {
        if (variant.getLabel() != null && !variant.getLabel().isBlank()) return variant.getLabel();
        return (variant.getPackSize() == null ? "" : variant.getPackSize())
                + (variant.getPackSizeUnit() == null ? "" : " " + variant.getPackSizeUnit())
                + (variant.getPackQuantity() == null || variant.getPackQuantity() <= 1 ? "" : " × " + variant.getPackQuantity());
    }

    private String normalizeId(String id) {
        String value = id.trim();
        return ObjectId.isValid(value) ? new ObjectId(value).toHexString() : value.toLowerCase(Locale.ROOT);
    }

    private int itemSequenceSafe(int value) { return value; }

    private record CartLine(Product product, ProductVariant variant, int quantity, BigDecimal unitPrice,
                            BigDecimal listPrice, BigDecimal discountTotal, BigDecimal lineTotal) {
        private BigDecimal listTotal() { return listPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP); }
    }

    private record StockUpdate(String productId, String variantId, Integer stock) { }
}
