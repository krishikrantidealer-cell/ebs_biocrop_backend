package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.CartItemRequest;
import com.ebs.biocrop.dto.request.CartSyncRequest;
import com.ebs.biocrop.dto.response.CartCountResponse;
import com.ebs.biocrop.dto.response.CartResponse;
import com.ebs.biocrop.dto.response.CheckoutSummaryResponse;
import com.ebs.biocrop.entity.Cart;
import com.ebs.biocrop.entity.CartItem;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.ProductStatus;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CartRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.CartService;
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
    private final UserRepository userRepository;

    public CartServiceImpl(CartRepository cartRepository,
                           ProductRepository productRepository,
                           UserRepository userRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
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
        getUser(phoneNumber);
        return cartRepository.findByPhoneNumber(phoneNumber)
                .map(cart -> new CartCountResponse(
                        cart.getItems() != null ? cart.getItems().size() : 0,
                        cart.getTotalQuantity() != null ? cart.getTotalQuantity() : 0
                ))
                .orElseGet(CartCountResponse::new);
    }

    @Override
    public CartResponse addToCart(String phoneNumber, CartItemRequest request) {
        Cart cart = getOrCreateCart(phoneNumber);
        Product product = resolveProduct(request.getVariationCode(), request.getProductId());

        validateProductAvailability(product);

        int minOrder = product.getMinOrderQty() != null && product.getMinOrderQty() > 0 ? product.getMinOrderQty() : 1;
        int requestedQty = request.getQuantity() != null && request.getQuantity() > 0 ? request.getQuantity() : minOrder;

        if (requestedQty < minOrder) {
            throw new AppException(String.format(
                    "Minimum order quantity for product '%s' is %d.", product.getName(), minOrder), HttpStatus.BAD_REQUEST);
        }

        String variationCode = product.getVariationCode();

        // Check if item already exists in cart -> increment quantity instead of adding duplicate row
        Optional<CartItem> existingItemOpt = cart.getItems().stream()
                .filter(item -> variationCode.equalsIgnoreCase(item.getVariationCode()))
                .findFirst();

        int availableStock = product.getStockQty() != null ? product.getStockQty() : 0;

        if (existingItemOpt.isPresent()) {
            CartItem existingItem = existingItemOpt.get();
            int newQuantity = existingItem.getQuantity() + requestedQty;

            if (newQuantity > availableStock) {
                throw new AppException(String.format(
                        "Cannot add %d item(s). Only %d available in stock and you already have %d in your cart.",
                        requestedQty, availableStock, existingItem.getQuantity()), HttpStatus.BAD_REQUEST);
            }

            existingItem.setQuantity(newQuantity);
            updateItemSnapshot(existingItem, product);
            log.info("Incremented quantity of item [{}] in cart to {}", variationCode, newQuantity);
        } else {
            if (requestedQty > availableStock) {
                throw new AppException(String.format(
                        "Requested quantity (%d) exceeds available stock (%d) for product '%s'.",
                        requestedQty, availableStock, product.getName()), HttpStatus.BAD_REQUEST);
            }

            CartItem newItem = new CartItem(
                    product.getId(),
                    product.getVariationCode(),
                    product.getName(),
                    product.getUnit(),
                    product.getUnitQty(),
                    product.getPrice(),
                    product.getSalePrice(),
                    product.getCourierCharge(),
                    requestedQty,
                    product.getStockQty(),
                    true,
                    product.getMinOrderQty()
            );
            cart.getItems().add(newItem);
            log.info("Added new item [{}] with qty {} to cart for phone [{}]", variationCode, requestedQty, phoneNumber);
        }

        recalculateTotals(cart);
        cart = cartRepository.save(cart);
        return CartResponse.fromEntity(cart);
    }

    @Override
    public CartResponse updateQuantity(String phoneNumber, String variationCode, Integer quantity) {
        Cart cart = getOrCreateCart(phoneNumber);

        CartItem item = cart.getItems().stream()
                .filter(i -> variationCode.equalsIgnoreCase(i.getVariationCode()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found with variationCode: " + variationCode));

        if (quantity == null || quantity <= 0) {
            cart.getItems().remove(item);
            log.info("Removed item [{}] from cart because quantity was set to 0", variationCode);
        } else {
            Product product = resolveProduct(variationCode, item.getProductId());
            int minOrder = product.getMinOrderQty() != null && product.getMinOrderQty() > 0 ? product.getMinOrderQty() : 1;

            if (quantity < minOrder) {
                throw new AppException(String.format(
                        "Minimum order quantity for product '%s' is %d. Set quantity to 0 to remove item.",
                        product.getName(), minOrder), HttpStatus.BAD_REQUEST);
            }

            int availableStock = product.getStockQty() != null ? product.getStockQty() : 0;

            if (quantity > availableStock) {
                throw new AppException(String.format(
                        "Requested quantity (%d) exceeds available stock (%d) for product '%s'.",
                        quantity, availableStock, product.getName()), HttpStatus.BAD_REQUEST);
            }

            item.setQuantity(quantity);
            updateItemSnapshot(item, product);
            log.info("Updated quantity of item [{}] to {}", variationCode, quantity);
        }

        recalculateTotals(cart);
        cart = cartRepository.save(cart);
        return CartResponse.fromEntity(cart);
    }

    @Override
    public CartResponse removeItem(String phoneNumber, String variationCode) {
        Cart cart = getOrCreateCart(phoneNumber);

        boolean removed = cart.getItems().removeIf(item -> variationCode.equalsIgnoreCase(item.getVariationCode()));
        if (!removed) {
            throw new ResourceNotFoundException("Cart item not found with variationCode: " + variationCode);
        }

        log.info("Removed item [{}] from cart for phone [{}]", variationCode, phoneNumber);
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
        log.info("Cleared all items from cart for phone [{}]", phoneNumber);
        return CartResponse.fromEntity(cart);
    }

    @Override
    public CartResponse syncCart(String phoneNumber, CartSyncRequest request) {
        Cart cart = getOrCreateCart(phoneNumber);

        if (request != null && request.getItems() != null) {
            for (CartItemRequest itemReq : request.getItems()) {
                try {
                    Product product = resolveProduct(itemReq.getVariationCode(), itemReq.getProductId());
                    if (product.getStatus() != ProductStatus.ACTIVE || Boolean.FALSE.equals(product.getInStock())) {
                        continue;
                    }

                    int requestedQty = itemReq.getQuantity() != null && itemReq.getQuantity() > 0 ? itemReq.getQuantity() : 1;
                    int availableStock = product.getStockQty() != null ? product.getStockQty() : 0;

                    Optional<CartItem> existingOpt = cart.getItems().stream()
                            .filter(i -> product.getVariationCode().equalsIgnoreCase(i.getVariationCode()))
                            .findFirst();

                    if (existingOpt.isPresent()) {
                        CartItem existing = existingOpt.get();
                        int mergedQty = Math.min(existing.getQuantity() + requestedQty, availableStock);
                        existing.setQuantity(mergedQty);
                        updateItemSnapshot(existing, product);
                    } else {
                        int safeQty = Math.min(requestedQty, availableStock);
                        if (safeQty > 0) {
                            CartItem newItem = new CartItem(
                                    product.getId(),
                                    product.getVariationCode(),
                                    product.getName(),
                                    product.getUnit(),
                                    product.getUnitQty(),
                                    product.getPrice(),
                                    product.getSalePrice(),
                                    product.getCourierCharge(),
                                    safeQty,
                                    product.getStockQty(),
                                    true
                            );
                            cart.getItems().add(newItem);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Skipping sync item [variation: {}, product: {}] due to error: {}",
                            itemReq.getVariationCode(), itemReq.getProductId(), e.getMessage());
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
        Cart cart = getOrCreateCart(phoneNumber);
        recalculateTotals(cart);
        cart = cartRepository.save(cart);

        List<String> validationErrors = new ArrayList<>();

        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            validationErrors.add("Your cart is empty. Please add items before proceeding to checkout.");
        } else {
            for (CartItem item : cart.getItems()) {
                if (Boolean.FALSE.equals(item.getInStock())) {
                    validationErrors.add(String.format("Item '%s' (%s) is out of stock or exceeds inventory.",
                            item.getProductName(), item.getVariationCode()));
                }
            }
        }

        if (user.getAddress() == null || user.getAddress().getPinCode() == null || user.getAddress().getPinCode().isBlank()) {
            validationErrors.add("A valid delivery address with pin code is required to proceed to checkout. Please update your profile.");
        }

        boolean readyForCheckout = validationErrors.isEmpty();

        return new CheckoutSummaryResponse(
                CartResponse.fromEntity(cart),
                readyForCheckout,
                validationErrors,
                user.getAddress(),
                cart.getTotalSalePrice(),
                cart.getTotalDiscount(),
                cart.getTotalCourierCharge(),
                cart.getFinalAmount()
        );
    }

    private Cart getOrCreateCart(String phoneNumber) {
        User user = getUser(phoneNumber);
        return cartRepository.findByPhoneNumber(phoneNumber)
                .orElseGet(() -> {
                    Cart newCart = new Cart(phoneNumber, user.getId());
                    return cartRepository.save(newCart);
                });
    }

    private User getUser(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("User", "phoneNumber", phoneNumber));

        if (Boolean.TRUE.equals(user.getIsDelete())) {
            throw new AppException("This account has been deactivated or deleted. Please contact support.", HttpStatus.FORBIDDEN);
        }
        return user;
    }

    private Product resolveProduct(String variationCode, String productId) {
        if (variationCode != null && !variationCode.isBlank()) {
            return productRepository.findByVariationCode(variationCode.trim())
                    .orElseThrow(() -> new ResourceNotFoundException("Product variation not found with variationCode: " + variationCode));
        }
        if (productId != null && !productId.isBlank()) {
            return productRepository.findById(productId.trim())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));
        }
        throw new AppException("Either variationCode or productId must be provided.", HttpStatus.BAD_REQUEST);
    }

    private void validateProductAvailability(Product product) {
        if (product.getStatus() != null && product.getStatus() != ProductStatus.ACTIVE) {
            throw new AppException(String.format("Product '%s' is currently inactive or unavailable.", product.getName()), HttpStatus.BAD_REQUEST);
        }
        if (Boolean.FALSE.equals(product.getInStock()) || (product.getStockQty() != null && product.getStockQty() <= 0)) {
            throw new AppException(String.format("Product '%s' is currently out of stock.", product.getName()), HttpStatus.BAD_REQUEST);
        }
    }

    private void updateItemSnapshot(CartItem item, Product product) {
        item.setPrice(product.getPrice());
        item.setSalePrice(product.getSalePrice());
        item.setCourierCharge(product.getCourierCharge());
        item.setStockQty(product.getStockQty());
        item.setMinOrderQty(product.getMinOrderQty());
        item.setInStock(Boolean.TRUE.equals(product.getInStock()) && product.getStockQty() != null && product.getStockQty() >= item.getQuantity());
        item.setSubtotal(item.getSalePrice() * item.getQuantity());
    }

    private void recalculateTotals(Cart cart) {
        double totalOriginalPrice = 0.0;
        double totalSalePrice = 0.0;
        double totalCourierCharge = 0.0;
        int totalQuantity = 0;

        if (cart.getItems() != null) {
            for (CartItem item : cart.getItems()) {
                // Real-time stock refresh from database
                productRepository.findByVariationCode(item.getVariationCode()).ifPresent(product -> {
                    updateItemSnapshot(item, product);
                });

                double price = item.getPrice() != null ? item.getPrice() : 0.0;
                double salePrice = item.getSalePrice() != null ? item.getSalePrice() : 0.0;
                double courierCharge = item.getCourierCharge() != null ? item.getCourierCharge() : 0.0;
                int qty = item.getQuantity() != null ? item.getQuantity() : 0;

                totalOriginalPrice += (price * qty);
                totalSalePrice += (salePrice * qty);
                totalCourierCharge += courierCharge;
                totalQuantity += qty;
            }
        }

        cart.setTotalOriginalPrice(round(totalOriginalPrice));
        cart.setTotalSalePrice(round(totalSalePrice));
        cart.setTotalDiscount(round(Math.max(0.0, totalOriginalPrice - totalSalePrice)));
        cart.setTotalCourierCharge(round(totalCourierCharge));
        cart.setFinalAmount(round(totalSalePrice + totalCourierCharge));
        cart.setTotalQuantity(totalQuantity);
        cart.setUpdatedAt(LocalDateTime.now());
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
