package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.PlaceOrderRequest;
import com.ebs.biocrop.dto.response.PlacedOrdersResponse;
import com.ebs.biocrop.dto.response.OrderResponse;
import com.ebs.biocrop.entity.*;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CartRepository;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.repository.ProductVariantLookup;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.OrderNumberGenerator;
import com.ebs.biocrop.service.OrderPlacementService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.mongodb.client.result.UpdateResult;
import org.bson.types.ObjectId;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class OrderPlacementServiceImpl implements OrderPlacementService {
    private final CartRepository carts;
    private final ProductVariantLookup variantLookup;
    private final UserRepository users;
    private final OrderRepository orders;
    private final OrderNumberGenerator orderNumbers;
    private final MongoTemplate mongoTemplate;
    private final RedisJsonCache redisCache;

    public OrderPlacementServiceImpl(CartRepository carts, ProductVariantLookup variantLookup,
                                     UserRepository users, OrderRepository orders,
                                     OrderNumberGenerator orderNumbers, MongoTemplate mongoTemplate,
                                     RedisJsonCache redisCache) {
        this.carts = carts;
        this.variantLookup = variantLookup;
        this.users = users;
        this.orders = orders;
        this.orderNumbers = orderNumbers;
        this.mongoTemplate = mongoTemplate;
        this.redisCache = redisCache;
    }

    @Override
    @Transactional
    public PlacedOrdersResponse createOrders(String customerId, PlaceOrderRequest request) {
        return createOrders(customerId, request, false, customerId);
    }

    @Override
    @Transactional
    public PlacedOrdersResponse createOrdersForAdmin(String customerId, PlaceOrderRequest request, String adminId) {
        return createOrders(customerId, request, true, adminId);
    }

    private PlacedOrdersResponse createOrders(String customerId, PlaceOrderRequest request, boolean adminOverride, String actorId) {
        BigDecimal advancePercentage = resolveAdvancePercentage(request, adminOverride);
        User customer = users.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", customerId));
        if (adminOverride && (customer.getRole() != UserRole.ROLE_CUSTOMER
                || Boolean.TRUE.equals(customer.getIsDeleted()) || Boolean.TRUE.equals(customer.getIsBlocked()))) {
            throw new AppException("Admin-assisted orders require an active customer account", HttpStatus.CONFLICT);
        }
        Cart cart = carts.findByUser(customerId)
                .orElseThrow(() -> new AppException("Cart is empty", HttpStatus.BAD_REQUEST));
        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new AppException("Cart is empty", HttpStatus.BAD_REQUEST);
        }
        if (cart.getAppliedCoupon() != null && !cart.getAppliedCoupon().isBlank()) {
            throw new AppException("Coupon checkout is not available yet; remove the coupon and try again", HttpStatus.CONFLICT);
        }
        if (cart.getFreeItems() != null && !cart.getFreeItems().isEmpty()) {
            throw new AppException("Promotional free items are not supported in checkout yet", HttpStatus.CONFLICT);
        }
        if (request.getPaymentMethod() == null || request.getShippingAddress() == null) {
            throw new AppException("Payment method and shipping address are required", HttpStatus.BAD_REQUEST);
        }

        List<String> variantIds = cart.getItems().stream().map(CartItem::getVariantId).filter(Objects::nonNull).toList();
        Set<String> requestedVariants = variantIds.stream().map(this::normalizeId).collect(Collectors.toSet());
        Map<String, Product> productsByVariant = new HashMap<>();
        for (Product product : variantLookup.findProductsContainingVariants(variantIds)) {
            if (product.getVariants() == null) continue;
            for (ProductVariant variant : product.getVariants()) {
                if (variant.getId() == null) continue;
                String key = normalizeId(variant.getId());
                if (!requestedVariants.contains(key)) continue;
                if (productsByVariant.putIfAbsent(key, product) != null) {
                    throw new AppException("A product variant is linked to more than one product", HttpStatus.CONFLICT);
                }
            }
        }

        Map<String, SellerOrderDraft> sellerDrafts = new LinkedHashMap<>();
        for (CartItem cartItem : cart.getItems()) {
            if (cartItem.getQuantity() == null || cartItem.getQuantity() <= 0 || cartItem.getVariantId() == null) {
                throw new AppException("Cart contains an invalid item", HttpStatus.CONFLICT);
            }
            Product product = productsByVariant.get(normalizeId(cartItem.getVariantId()));
            ProductVariant variant = product == null ? null : findVariant(product, cartItem.getVariantId());
            if (product == null || variant == null) {
                throw new ResourceNotFoundException("Product variant", "id", cartItem.getVariantId());
            }
            if (!"ACTIVE".equalsIgnoreCase(product.getStatus()) || !Boolean.TRUE.equals(product.getIsAvailable())
                    || product.getSellerId() == null || product.getSellerId().isBlank()) {
                throw new AppException("A cart product is no longer available", HttpStatus.CONFLICT);
            }
            if (variant.getStock() == null || variant.getStock() < cartItem.getQuantity()) {
                throw new AppException("Insufficient stock for product '" + product.getTitle() + "'", HttpStatus.CONFLICT);
            }

            double unitPrice = requirePrice(variant.getEffectivePrice(), product.getTitle());
            double listPrice = variant.getPrintedMrp() == null ? unitPrice : requirePrice(variant.getPrintedMrp(), product.getTitle());
            OrderItem item = createItem(product, variant, cartItem.getQuantity(), unitPrice, listPrice);
            SellerOrderDraft draft = sellerDrafts.computeIfAbsent(product.getSellerId(), SellerOrderDraft::new);
            draft.items.add(item);
            draft.subtotal = draft.subtotal.add(BigDecimal.valueOf(listPrice).multiply(BigDecimal.valueOf(cartItem.getQuantity())));
            draft.discount = draft.discount.add(BigDecimal.valueOf(Math.max(0.0, listPrice - unitPrice))
                    .multiply(BigDecimal.valueOf(cartItem.getQuantity())));
            draft.total = draft.total.add(BigDecimal.valueOf(unitPrice).multiply(BigDecimal.valueOf(cartItem.getQuantity())));
            draft.stockLines.add(new StockLine(product.getId(), variant.getId(), cartItem.getQuantity(), product.getTitle()));
        }

        Set<String> sellerIds = sellerDrafts.keySet();
        Map<String, User> sellersById = StreamSupport.stream(users.findAllById(sellerIds).spliterator(), false)
                .collect(Collectors.toMap(User::getId, user -> user));
        if (sellersById.size() != sellerIds.size()
                || sellersById.values().stream().anyMatch(seller -> seller.getRole() != UserRole.ROLE_SELLER
                    || Boolean.TRUE.equals(seller.getIsDeleted()) || Boolean.TRUE.equals(seller.getIsBlocked()))) {
            throw new AppException("A seller for a cart product is unavailable", HttpStatus.CONFLICT);
        }

        List<StockLine> stockLines = sellerDrafts.values().stream().flatMap(draft -> draft.stockLines.stream())
                .sorted(java.util.Comparator.comparing(StockLine::productId).thenComparing(StockLine::variantId)).toList();
        for (StockLine stockLine : stockLines) reserveStock(stockLine);

        OrderAddressSnapshot shipping = snapshot(request.getShippingAddress());
        OrderAddressSnapshot billing = request.getBillingAddress() == null
                ? copyAddress(shipping) : snapshot(request.getBillingAddress());
        String checkoutGroupId = new ObjectId().toHexString();
        LocalDateTime placedAt = LocalDateTime.now();
        List<Order> created = new ArrayList<>();

        for (SellerOrderDraft draft : sellerDrafts.values()) {
            User seller = sellersById.get(draft.sellerId);
            Order order = new Order();
            order.setOrderNumber(orderNumbers.nextForSeller(draft.sellerId));
            order.setCheckoutGroupId(checkoutGroupId);
            order.setCustomerId(customerId);
            order.setCustomerSnapshot(customerSnapshot(customer));
            order.setSellerId(draft.sellerId);
            order.setSellerSnapshot(sellerSnapshot(seller));
            order.setItems(draft.items);
            order.setSubtotalAmount(round(draft.subtotal));
            order.setDiscountAmount(round(draft.discount));
            order.setShippingAmount(0.0); // Shipping is not currently calculated by this backend.
            order.setTotalAmount(round(draft.total));
            order.setPaymentMethod(request.getPaymentMethod());
            order.setAdvancePercentage(advancePercentage.doubleValue());
            BigDecimal advanceDue = BigDecimal.valueOf(order.getTotalAmount()).multiply(advancePercentage)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (request.getPaymentMethod() == OrderPaymentMethod.PARTIAL && advanceDue.signum() <= 0) {
                throw new AppException("Advance amount rounds to zero for this order total", HttpStatus.BAD_REQUEST);
            }
            order.setAdvanceAmountDue(advanceDue.doubleValue());
            // Null means no payment has been verified yet; selection of a method isn't settlement.
            order.setPaymentStatus(null);
            OrderPaymentDetails payment = new OrderPaymentDetails();
            double total = round(draft.total);
            payment.recordAmounts(total, 0.0);
            order.setPayment(payment);
            order.setOrderStatus(OrderStatus.ORDERED.name());
            order.setStockReserved(true);
            order.setShippingAddress(copyAddress(shipping));
            order.setBillingAddress(copyAddress(billing));
            OrderStatusEvent initialStatus = new OrderStatusEvent();
            initialStatus.setStatus(OrderStatus.ORDERED.name());
            initialStatus.setChangedAt(placedAt);
            initialStatus.setChangedBy(actorId);
            initialStatus.setNote(adminOverride ? "Order placed by admin for customer" : "Order placed");
            order.setStatusHistory(List.of(initialStatus));
            order.setPlacedAt(placedAt);
            order.setCreatedAt(placedAt);
            order.setUpdatedAt(placedAt);
            created.add(order);
        }

        List<Order> saved = orders.saveAll(created);
        carts.delete(cart);
        redisCache.invalidateRegionAfterCommit("public-products");
        return new PlacedOrdersResponse(checkoutGroupId, saved.stream()
                .map(order -> adminOverride ? OrderResponse.forAdmin(order) : OrderResponse.forCustomer(order))
                .toList());
    }

    private BigDecimal resolveAdvancePercentage(PlaceOrderRequest request, boolean adminOverride) {
        if (request == null || request.getPaymentMethod() == null) {
            throw new AppException("Payment method is required", HttpStatus.BAD_REQUEST);
        }
        BigDecimal requested = request.getAdvancePercentage();
        if (request.getPaymentMethod() == OrderPaymentMethod.PREPAID) {
            if (requested != null) throw new AppException("Do not send advancePercentage for PREPAID orders", HttpStatus.BAD_REQUEST);
            return BigDecimal.valueOf(100);
        }
        if (requested == null || requested.stripTrailingZeros().scale() > 0) {
            throw new AppException("PARTIAL orders require a whole-number advance percentage", HttpStatus.BAD_REQUEST);
        }
        if (requested.compareTo(BigDecimal.ONE) < 0 || requested.compareTo(BigDecimal.valueOf(99)) > 0) {
            throw new AppException("Advance percentage must be from 1 through 99", HttpStatus.BAD_REQUEST);
        }
        if (!adminOverride && !List.of(10, 20, 50).contains(requested.intValueExact())) {
            throw new AppException("Customer partial payment options are 10%, 20%, or 50%", HttpStatus.BAD_REQUEST);
        }
        return requested;
    }

    private void reserveStock(StockLine line) {
        if (!ObjectId.isValid(line.productId) || !ObjectId.isValid(line.variantId)) {
            throw new AppException("Product identifiers must be valid MongoDB ObjectIds", HttpStatus.CONFLICT);
        }
        Query stockQuery = Query.query(Criteria.where("_id").is(new ObjectId(line.productId))
                .and("variants").elemMatch(Criteria.where("_id").is(new ObjectId(line.variantId))
                        .and("stock").gte(line.quantity)));
        UpdateResult result = mongoTemplate.updateFirst(stockQuery,
                new Update().inc("variants.$.stock", -line.quantity), Product.class);
        if (result.getModifiedCount() != 1) {
            throw new AppException("Insufficient stock for product '" + line.title + "'", HttpStatus.CONFLICT);
        }
    }

    private OrderItem createItem(Product product, ProductVariant variant, int quantity, double unitPrice, double listPrice) {
        OrderItem item = new OrderItem();
        item.setId(new ObjectId().toHexString());
        item.setProductId(product.getId());
        item.setVariantId(variant.getId());
        item.setProductCode(product.getProductCode());
        item.setSku(product.getSku());
        item.setTitle(product.getTitle());
        item.setVendor(product.getVendor());
        item.setImage(product.getImages() == null || product.getImages().isEmpty() ? null : product.getImages().getFirst());
        item.setVariant(variantLabel(variant));
        item.setQuantity(quantity);
        item.setListPrice(listPrice);
        item.setUnitPrice(unitPrice);
        item.setLineTotal(round(BigDecimal.valueOf(unitPrice).multiply(BigDecimal.valueOf(quantity))));
        return item;
    }

    private ProductVariant findVariant(Product product, String variantId) {
        return product.getVariants().stream().filter(variant -> variant.getId() != null
                && normalizeId(variant.getId()).equals(normalizeId(variantId))).findFirst().orElse(null);
    }

    private String variantLabel(ProductVariant variant) {
        if (variant.getLabel() != null && !variant.getLabel().isBlank()) return variant.getLabel();
        return (variant.getPackSize() == null ? "" : variant.getPackSize())
                + (variant.getPackSizeUnit() == null ? "" : " " + variant.getPackSizeUnit())
                + (variant.getPackQuantity() == null || variant.getPackQuantity() <= 1 ? "" : " × " + variant.getPackQuantity());
    }

    private OrderCustomerSnapshot customerSnapshot(User customer) {
        OrderCustomerSnapshot snapshot = new OrderCustomerSnapshot();
        snapshot.setName(fullName(customer));
        snapshot.setPhoneNumber(customer.getPhoneNumber());
        snapshot.setCompanyName(customer.getShopName());
        snapshot.setGstNumber(customer.getGstNumber());
        return snapshot;
    }

    private OrderSellerSnapshot sellerSnapshot(User seller) {
        OrderSellerSnapshot snapshot = new OrderSellerSnapshot();
        snapshot.setDisplayName(seller.getShopName() == null || seller.getShopName().isBlank() ? fullName(seller) : seller.getShopName());
        snapshot.setPhoneNumber(seller.getPhoneNumber());
        snapshot.setEmail(seller.getEmail());
        snapshot.setGstNumber(seller.getGstNumber());
        return snapshot;
    }

    private String fullName(User user) {
        return java.util.stream.Stream.of(user.getFirstName(), user.getLastName()).filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "));
    }

    private OrderAddressSnapshot snapshot(PlaceOrderRequest.AddressInput input) {
        OrderAddressSnapshot address = new OrderAddressSnapshot();
        address.setName(input.getName().trim());
        address.setPhoneNumber(input.getPhoneNumber().trim());
        address.setVillageArea(input.getVillageArea().trim());
        address.setAddressLine2(input.getAddressLine2() == null ? null : input.getAddressLine2().trim());
        address.setCityTehsil(input.getCityTehsil().trim());
        address.setState(input.getState().trim());
        address.setPincode(input.getPincode());
        return address;
    }

    private OrderAddressSnapshot copyAddress(OrderAddressSnapshot source) {
        OrderAddressSnapshot copy = new OrderAddressSnapshot();
        copy.setName(source.getName());
        copy.setPhoneNumber(source.getPhoneNumber());
        copy.setVillageArea(source.getVillageArea());
        copy.setAddressLine2(source.getAddressLine2());
        copy.setCityTehsil(source.getCityTehsil());
        copy.setState(source.getState());
        copy.setPincode(source.getPincode());
        return copy;
    }

    private double requirePrice(Double price, String productTitle) {
        if (price == null || !Double.isFinite(price) || price < 0) {
            throw new AppException("Product '" + productTitle + "' has an invalid price", HttpStatus.CONFLICT);
        }
        return price;
    }

    private String normalizeId(String id) {
        String value = id.trim();
        return ObjectId.isValid(value) ? new ObjectId(value).toHexString() : value.toLowerCase(Locale.ROOT);
    }

    private double round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static class SellerOrderDraft {
        private final String sellerId;
        private final List<OrderItem> items = new ArrayList<>();
        private final List<StockLine> stockLines = new ArrayList<>();
        private BigDecimal subtotal = BigDecimal.ZERO;
        private BigDecimal discount = BigDecimal.ZERO;
        private BigDecimal total = BigDecimal.ZERO;

        private SellerOrderDraft(String sellerId) { this.sellerId = sellerId; }
    }

    private record StockLine(String productId, String variantId, int quantity, String title) { }
}
