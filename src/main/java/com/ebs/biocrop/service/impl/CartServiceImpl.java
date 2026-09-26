package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.CartItemRequest;
import com.ebs.biocrop.dto.request.CartSyncRequest;
import com.ebs.biocrop.dto.response.CartCountResponse;
import com.ebs.biocrop.dto.response.CartResponse;
import com.ebs.biocrop.dto.response.CheckoutSummaryResponse;
import com.ebs.biocrop.entity.Cart;
import com.ebs.biocrop.entity.CartItem;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CartRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.ProductVariantLookup;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.CartService;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class CartServiceImpl implements CartService {

    private static final Logger log = LoggerFactory.getLogger(CartServiceImpl.class);

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final ProductVariantLookup productVariantLookup;
    private final UserRepository userRepository;

    public CartServiceImpl(CartRepository cartRepository,
                           ProductRepository productRepository,
                           ProductVariantLookup productVariantLookup,
                           UserRepository userRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.productVariantLookup = productVariantLookup;
        this.userRepository = userRepository;
    }

    @Override
    public CartResponse getCart(String phoneNumber) {
        Cart cart = getOrCreateCart(phoneNumber);
        recalculateTotals(cart);
        cart = cartRepository.save(cart);
        return CartResponse.fromEntity(cart);
    }

    @Override
    public CartCountResponse getCartCount(String phoneNumber) {
        User user = getUser(phoneNumber);
        return cartRepository.findByUser(user.getId())
                .map(cart -> {
                    int totalItems = cart.getItems() != null ? cart.getItems().size() : 0;
                    int totalQty = cart.getItems() != null ? cart.getItems().stream().mapToInt(CartItem::getQuantity).sum() : 0;
                    return new CartCountResponse(totalItems, totalQty);
                })
                .orElseGet(CartCountResponse::new);
    }

    @Override
    public CartResponse addToCart(String phoneNumber, CartItemRequest request) {
        Cart cart = getOrCreateCart(phoneNumber);
        
        ProductResolution resolution = resolveProductAndVariant(request.getVariantId());
        Product product = resolution.product;
        ProductVariant variant = resolution.variant;

        validateProductAvailability(product, variant);

        int minOrder = variant.getMinOrderQty() != null && variant.getMinOrderQty() > 0 ? variant.getMinOrderQty() : 1;
        int requestedQty = request.getQuantity() != null && request.getQuantity() > 0 ? request.getQuantity() : minOrder;

        if (requestedQty < minOrder) {
            throw new AppException(String.format(
                    "Minimum order quantity for product '%s' is %d.", product.getTitle(), minOrder), HttpStatus.BAD_REQUEST);
        }

        String variantId = variant.getId();

        Optional<CartItem> existingItemOpt = cart.getItems().stream()
                .filter(item -> variantId.equalsIgnoreCase(item.getVariantId()))
                .findFirst();

        int availableStock = variant.getStockQty() != null ? variant.getStockQty() : 0;

        if (existingItemOpt.isPresent()) {
            CartItem existingItem = existingItemOpt.get();
            int newQuantity = existingItem.getQuantity() + requestedQty;

            if (newQuantity > availableStock) {
                throw new AppException(String.format(
                        "Cannot add %d item(s). Only %d available in stock and you already have %d in your cart.",
                        requestedQty, availableStock, existingItem.getQuantity()), HttpStatus.BAD_REQUEST);
            }

            existingItem.setQuantity(newQuantity);
            existingItem.setPrice(effectiveSalePrice(variant));
            log.info("Incremented quantity of item [{}] in cart to {}", variantId, newQuantity);
        } else {
            if (requestedQty > availableStock) {
                throw new AppException(String.format(
                        "Requested quantity (%d) exceeds available stock (%d) for product '%s'.",
                        requestedQty, availableStock, product.getTitle()), HttpStatus.BAD_REQUEST);
            }

            CartItem newItem = new CartItem(
                    product.getId(),
                    variant.getId(),
                    requestedQty,
                    effectiveSalePrice(variant)
            );
            cart.getItems().add(newItem);
            log.info("Added new item [{}] with qty {} to cart for user [{}]", variantId, requestedQty, cart.getUser());
        }

        recalculateTotals(cart);
        cart = cartRepository.save(cart);
        return CartResponse.fromEntity(cart);
    }

    @Override
    public CartResponse updateQuantity(String phoneNumber, String variantId, Integer quantity) {
        String normalizedVariantId = normalizeVariantId(variantId);
        Cart cart = getOrCreateCart(phoneNumber);

        CartItem item = cart.getItems().stream()
                .filter(i -> normalizedVariantId.equalsIgnoreCase(i.getVariantId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found with variantId: " + normalizedVariantId));

        if (quantity == null || quantity <= 0) {
            cart.getItems().remove(item);
            log.info("Removed item [{}] from cart because quantity was set to 0", normalizedVariantId);
        } else {
            ProductResolution resolution = resolveProductAndVariant(item.getVariantId());
            Product product = resolution.product;
            ProductVariant variant = resolution.variant;
            
            int minOrder = variant.getMinOrderQty() != null && variant.getMinOrderQty() > 0 ? variant.getMinOrderQty() : 1;

            if (quantity < minOrder) {
                throw new AppException(String.format(
                        "Minimum order quantity for product '%s' is %d. Set quantity to 0 to remove item.",
                        product.getTitle(), minOrder), HttpStatus.BAD_REQUEST);
            }

            int availableStock = variant.getStockQty() != null ? variant.getStockQty() : 0;

            if (quantity > availableStock) {
                throw new AppException(String.format(
                        "Requested quantity (%d) exceeds available stock (%d) for product '%s'.",
                        quantity, availableStock, product.getTitle()), HttpStatus.BAD_REQUEST);
            }

            item.setQuantity(quantity);
            item.setPrice(effectiveSalePrice(variant));
            log.info("Updated quantity of item [{}] to {}", normalizedVariantId, quantity);
        }

        recalculateTotals(cart);
        cart = cartRepository.save(cart);
        return CartResponse.fromEntity(cart);
    }

    @Override
    public CartResponse removeItem(String phoneNumber, String variantId) {
        String normalizedVariantId = normalizeVariantId(variantId);
        Cart cart = getOrCreateCart(phoneNumber);

        boolean removed = cart.getItems().removeIf(item -> normalizedVariantId.equalsIgnoreCase(item.getVariantId()));
        if (!removed) {
            throw new ResourceNotFoundException("Cart item not found with variantId: " + normalizedVariantId);
        }

        log.info("Removed item [{}] from cart for user [{}]", normalizedVariantId, cart.getUser());
        recalculateTotals(cart);
        cart = cartRepository.save(cart);
        return CartResponse.fromEntity(cart);
    }

    @Override
    public CartResponse clearCart(String phoneNumber) {
        Cart cart = getOrCreateCart(phoneNumber);
        cart.getItems().clear();
        recalculateTotals(cart);
        cart = cartRepository.save(cart);
        log.info("Cleared all items from cart for user [{}]", cart.getUser());
        return CartResponse.fromEntity(cart);
    }

    @Override
    public CartResponse syncCart(String phoneNumber, CartSyncRequest request) {
        Cart cart = getOrCreateCart(phoneNumber);

        if (request != null && request.getItems() != null) {
            for (CartItemRequest itemReq : request.getItems()) {
                try {
                    ProductResolution resolution = resolveProductAndVariant(itemReq.getVariantId());
                    Product product = resolution.product;
                    ProductVariant variant = resolution.variant;
                    
                    if (!"In Stock".equalsIgnoreCase(product.getAvailabilityStatus())) {
                        continue;
                    }

                    int requestedQty = itemReq.getQuantity() != null && itemReq.getQuantity() > 0 ? itemReq.getQuantity() : 1;
                    int availableStock = variant.getStockQty() != null ? variant.getStockQty() : 0;

                    Optional<CartItem> existingOpt = cart.getItems().stream()
                            .filter(i -> variant.getId().equalsIgnoreCase(i.getVariantId()))
                            .findFirst();

                    if (existingOpt.isPresent()) {
                        CartItem existing = existingOpt.get();
                        int mergedQty = Math.min(existing.getQuantity() + requestedQty, availableStock);
                        existing.setQuantity(mergedQty);
                        existing.setPrice(effectiveSalePrice(variant));
                    } else {
                        int safeQty = Math.min(requestedQty, availableStock);
                        if (safeQty > 0) {
                            CartItem newItem = new CartItem(
                                    product.getId(),
                                    variant.getId(),
                                    safeQty,
                                    effectiveSalePrice(variant)
                            );
                            cart.getItems().add(newItem);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Skipping cart sync item [variantId: {}] due to error: {}",
                            itemReq.getVariantId(), e.getMessage());
                }
            }
        }

        recalculateTotals(cart);
        cart = cartRepository.save(cart);
        return CartResponse.fromEntity(cart);
    }

    @Override
    public CheckoutSummaryResponse getCheckoutSummary(String phoneNumber) {
        User user = getUser(phoneNumber);
        if (!Boolean.TRUE.equals(user.getIsProfileComplete())) {
            throw new AppException("Complete your profile, including delivery address, before checkout.", HttpStatus.BAD_REQUEST);
        }
        Cart cart = getOrCreateCart(phoneNumber);
        recalculateTotals(cart);
        cart = cartRepository.save(cart);

        List<String> validationErrors = new ArrayList<>();

        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            validationErrors.add("Your cart is empty. Please add items before proceeding to checkout.");
        } else {
            for (CartItem item : cart.getItems()) {
                try {
                    ProductResolution res = resolveProductAndVariant(item.getVariantId());
                    if (!"In Stock".equalsIgnoreCase(res.product.getAvailabilityStatus()) || res.variant.getStockQty() == null || res.variant.getStockQty() < item.getQuantity()) {
                        validationErrors.add(String.format("Item '%s' (%s) is out of stock or exceeds inventory.",
                                res.product.getTitle(), item.getVariantId()));
                    }
                } catch (Exception e) {
                    validationErrors.add(String.format("Item '%s' is no longer available.", item.getVariantId()));
                }
            }
        }

        if (user.getAddress() == null || user.getAddress().getPincode() == null || user.getAddress().getPincode().isBlank()) {
            validationErrors.add("A valid delivery address with pin code is required to proceed to checkout. Please update your profile.");
        }

        boolean readyForCheckout = validationErrors.isEmpty();
        
        // To compute checkout totals correctly we need to re-fetch courier charges since they were removed from Cart entity
        BigDecimal totalCourier = BigDecimal.ZERO;
        for (CartItem item : cart.getItems()) {
             try {
                ProductResolution res = resolveProductAndVariant(item.getVariantId());
                 totalCourier = totalCourier.add(amount(res.variant.getCourierCharge()));
             } catch (Exception ignored) {}
        }

        return new CheckoutSummaryResponse(
                CartResponse.fromEntity(cart),
                readyForCheckout,
                validationErrors,
                user.getAddress(),
                cart.getTotalAmount(),
                cart.getDiscountAmount(),
                round(totalCourier),
                cart.getFinalAmount()
        );
    }

    private Cart getOrCreateCart(String phoneNumber) {
        User user = getUser(phoneNumber);
        Cart cart = cartRepository.findByUser(user.getId())
                .orElseGet(() -> {
                    Cart newCart = new Cart(user.getId());
                    return cartRepository.save(newCart);
                });
        normalizeStoredVariantIds(cart);
        return cart;
    }

    private User getUser(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("User", "phoneNumber", phoneNumber));

        if (Boolean.TRUE.equals(user.getIsDeleted())) {
            throw new AppException("This account has been deactivated or deleted. Please contact support.", HttpStatus.FORBIDDEN);
        }
        return user;
    }

    private ProductResolution resolveProductAndVariant(String variantId) {
        String normalizedVariantId = normalizeVariantId(variantId);
        List<ProductResolution> matches = findProductsByVariantId(normalizedVariantId);
        if (matches.isEmpty()) {
            throw new ResourceNotFoundException("Product variant", "id", normalizedVariantId);
        }
        if (matches.size() > 1) {
            throw new AppException("Variant ID is assigned to more than one product.", HttpStatus.CONFLICT);
        }
        return matches.getFirst();
    }

    private String normalizeVariantId(String variantId) {
        if (variantId == null || !ObjectId.isValid(variantId.trim())) {
            throw new AppException("Variant ID must be a valid MongoDB ObjectId.", HttpStatus.BAD_REQUEST);
        }
        return new ObjectId(variantId.trim()).toHexString();
    }

    private List<ProductResolution> findProductsByVariantId(String variantId) {
        List<ProductResolution> matches = new ArrayList<>();
        for (Product product : productVariantLookup.findProductsContainingVariants(List.of(variantId))) {
            if (product.getVariants() == null) {
                continue;
            }
            for (ProductVariant variant : product.getVariants()) {
                if (variant.getId() != null && variantId.equalsIgnoreCase(variant.getId())) {
                    matches.add(new ProductResolution(product, variant));
                }
            }
        }
        return matches;
    }

    private void normalizeStoredVariantIds(Cart cart) {
        if (cart.getItems() == null) {
            return;
        }
        for (CartItem item : cart.getItems()) {
            if (item.getVariantId() == null || item.getVariantId().isBlank()) {
                continue;
            }
            try {
                ProductResolution resolution = resolveStoredVariant(item);
                item.setProduct(resolution.product.getId());
                item.setVariantId(resolution.variant.getId());
            } catch (AppException ignored) {
                // Keep unavailable legacy cart entries intact so a catalog issue does not silently discard a user's cart.
            }
        }
    }

    private ProductResolution resolveStoredVariant(CartItem item) {
        String storedId = item.getVariantId().trim();
        if (ObjectId.isValid(storedId)) {
            List<ProductResolution> byId = findProductsByVariantId(new ObjectId(storedId).toHexString());
            if (byId.size() == 1) {
                return byId.getFirst();
            }
            if (byId.size() > 1) {
                throw new AppException("Variant ID is not unique in the product catalog.", HttpStatus.CONFLICT);
            }
        }

        Product product = item.getProduct() == null
                ? null
                : productRepository.findById(item.getProduct()).orElse(null);
        if (product == null) {
            product = productRepository.findByVariantsVariationCode(storedId).orElse(null);
        }
        if (product != null && product.getVariants() != null) {
            List<ProductVariant> matches = product.getVariants().stream()
                    .filter(variant -> storedId.equalsIgnoreCase(variant.getVariationCode()))
                    .toList();
            if (matches.size() == 1 && matches.getFirst().getId() != null) {
                return new ProductResolution(product, matches.getFirst());
            }
            if (matches.size() > 1) {
                throw new AppException("Legacy variation code matches multiple variants.", HttpStatus.CONFLICT);
            }
        }
        throw new ResourceNotFoundException("Product variant", "id or legacy variationCode", storedId);
    }

    private void validateProductAvailability(Product product, ProductVariant variant) {
        if (!"In Stock".equalsIgnoreCase(product.getAvailabilityStatus())) {
            throw new AppException(String.format("Product '%s' is currently inactive or unavailable.", product.getTitle()), HttpStatus.BAD_REQUEST);
        }
        if (variant.getStockQty() == null || variant.getStockQty() <= 0) {
            throw new AppException(String.format("Product '%s' is currently out of stock.", product.getTitle()), HttpStatus.BAD_REQUEST);
        }
    }

    private void recalculateTotals(Cart cart) {
        BigDecimal totalOriginalPrice = BigDecimal.ZERO;
        BigDecimal totalSalePrice = BigDecimal.ZERO;
        BigDecimal totalCourierCharge = BigDecimal.ZERO;

        if (cart.getItems() != null) {
            for (CartItem item : cart.getItems()) {
                // Real-time stock refresh from database
             try {
                ProductResolution res = resolveProductAndVariant(item.getVariantId());
                    Double salePrice = effectiveSalePrice(res.variant);
                    item.setPrice(salePrice);

                    int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                    BigDecimal quantity = BigDecimal.valueOf(qty);

                    BigDecimal originalUnitPrice = res.variant.getCompareAtPrice() != null
                            ? BigDecimal.valueOf(res.variant.getCompareAtPrice())
                            : amount(res.variant.getPrice());
                    totalOriginalPrice = totalOriginalPrice.add(originalUnitPrice.multiply(quantity));
                    totalSalePrice = totalSalePrice.add(amount(salePrice).multiply(quantity));
                    totalCourierCharge = totalCourierCharge.add(amount(res.variant.getCourierCharge()));
                } catch (Exception ignored) {
                }
            }
        }

        cart.setTotalAmount(round(totalOriginalPrice));
        BigDecimal discount = totalOriginalPrice.subtract(totalSalePrice).max(BigDecimal.ZERO);
        cart.setDiscountAmount(round(discount));
        cart.setFinalAmount(round(totalSalePrice.add(totalCourierCharge)));
        cart.setUpdatedAt(LocalDateTime.now());
    }

    private BigDecimal amount(Double value) {
        return value != null ? BigDecimal.valueOf(value) : BigDecimal.ZERO;
    }

    private Double effectiveSalePrice(ProductVariant variant) {
        return variant.getSalePrice() != null ? variant.getSalePrice() : variant.getPrice();
    }

    private double round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
    
    private static class ProductResolution {
        Product product;
        ProductVariant variant;

        public ProductResolution(Product product, ProductVariant variant) {
            this.product = product;
            this.variant = variant;
        }
    }
}
