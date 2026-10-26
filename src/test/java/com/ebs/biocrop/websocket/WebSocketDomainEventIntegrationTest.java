package com.ebs.biocrop.websocket;

import com.ebs.biocrop.dto.request.AdminProductVariantPatchRequest;
import com.ebs.biocrop.dto.request.CategoryWriteRequest;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.OrderStatus;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.repository.ProductCollectionRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.service.CartService;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.service.ProductImageVariantService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.service.SellerProductService;
import com.ebs.biocrop.service.impl.AdminProductServiceImpl;
import com.ebs.biocrop.service.impl.CategoryServiceImpl;
import com.ebs.biocrop.service.impl.OrderLifecycleServiceImpl;
import com.ebs.biocrop.entity.Cart;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.repository.CartRepository;
import com.ebs.biocrop.repository.ProductVariantLookup;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.impl.AdminUserServiceImpl;
import com.ebs.biocrop.service.impl.CartServiceImpl;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketDomainEventIntegrationTest {

    @Mock
    private WebSocketService webSocketService;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private RedisJsonCache redisCache;

    @Mock
    private GcsImageStorageService imageStorage;

    @Mock
    private ProductImageVariantService imageValidator;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductCollectionRepository collectionRepository;

    @Mock
    private SellerProductService sellerProductService;

    @Mock
    private CartService cartService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductVariantLookup productVariantLookup;

    @Test
    void categoryCreate_shouldBroadcastCategoriesUpdateEvent() {
        CategoryServiceImpl categoryService = new CategoryServiceImpl(
                categoryRepository, redisCache, imageStorage, imageValidator, mongoTemplate, webSocketService
        );

        CategoryWriteRequest request = new CategoryWriteRequest();
        request.setName("Bio-Insecticides");
        request.setSlug("bio-insecticides");

        Category savedCategory = new Category();
        savedCategory.setId("cat-123");
        savedCategory.setName("Bio-Insecticides");
        savedCategory.setLevel(0);

        when(categoryRepository.existsSlugUnderParent(null, "bio-insecticides", null)).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenReturn(savedCategory);

        categoryService.create(request);

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        verify(webSocketService).broadcast(eventCaptor.capture(), payloadCaptor.capture());

        assertEquals("categories:update", eventCaptor.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payloadCaptor.getValue();
        assertEquals("cat-123", data.get("categoryId"));
        assertEquals("Bio-Insecticides", data.get("name"));
        assertEquals(0, data.get("level"));
    }

    @Test
    void orderStatusUpdate_shouldBroadcastOrdersStatusChangeEvent() {
        OrderLifecycleServiceImpl lifecycleService = new OrderLifecycleServiceImpl(
                orderRepository, mongoTemplate, redisCache, webSocketService
        );

        Order existingOrder = new Order();
        existingOrder.setId("ord-999");
        existingOrder.setOrderStatus(OrderStatus.ORDERED.name());

        when(orderRepository.findById("ord-999")).thenReturn(Optional.of(existingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        lifecycleService.updateStatusForAdmin("admin-1", "ord-999", OrderStatus.CONFIRMED, "Payment verified");

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        verify(webSocketService).broadcast(eventCaptor.capture(), payloadCaptor.capture());

        assertEquals("orders:status_change", eventCaptor.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payloadCaptor.getValue();
        assertEquals("ord-999", data.get("orderId"));
        assertEquals("ORDERED", data.get("oldStatus"));
        assertEquals("CONFIRMED", data.get("newStatus"));
    }

    @Test
    void productVariantStockPatch_shouldBroadcastInventoryStockUpdateEvent() {
        AdminProductServiceImpl adminProductService = new AdminProductServiceImpl(
                productRepository, collectionRepository, categoryRepository,
                imageStorage, imageValidator, redisCache, sellerProductService,
                mongoTemplate, cartService, webSocketService
        );

        String productId = new ObjectId().toHexString();
        String variantId = new ObjectId().toHexString();

        Product product = new Product();
        product.setId(productId);
        product.setIsDeleted(false);
        ProductVariant variant = new ProductVariant();
        variant.setId(variantId);
        variant.setStock(10);
        product.setVariants(new ArrayList<>(List.of(variant)));

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminProductVariantPatchRequest patch = new AdminProductVariantPatchRequest();
        patch.setStock(45);

        adminProductService.patchVariant(productId, variantId, patch);

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        // Verify both products:update and inventory:stock_update were broadcast
        verify(webSocketService, atLeastOnce()).broadcast(eventCaptor.capture(), payloadCaptor.capture());

        List<String> events = eventCaptor.getAllValues();
        assertEquals(true, events.contains("inventory:stock_update"));

        int stockUpdateIndex = events.indexOf("inventory:stock_update");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payloadCaptor.getAllValues().get(stockUpdateIndex);
        assertEquals(productId, data.get("productId"));
        assertEquals(variantId, data.get("variantId"));
        assertEquals(45, data.get("stock"));
    }

    @Test
    void productSoftDelete_shouldBroadcastProductsDeleteEvent() {
        AdminProductServiceImpl adminProductService = new AdminProductServiceImpl(
                productRepository, collectionRepository, categoryRepository,
                imageStorage, imageValidator, redisCache, sellerProductService,
                mongoTemplate, cartService, webSocketService
        );

        String productId = new ObjectId().toHexString();
        Product product = new Product();
        product.setId(productId);
        product.setIsDeleted(false);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        adminProductService.softDelete(productId);

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        verify(webSocketService, atLeastOnce()).broadcast(eventCaptor.capture(), payloadCaptor.capture());

        List<String> events = eventCaptor.getAllValues();
        assertEquals(true, events.contains("products:delete"));

        int deleteIndex = events.indexOf("products:delete");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payloadCaptor.getAllValues().get(deleteIndex);
        assertEquals(productId, data.get("productId"));
        assertEquals(false, data.get("isPermanent"));
    }

    @Test
    void productReviewStatus_shouldNotifySeller() {
        AdminProductServiceImpl adminProductService = new AdminProductServiceImpl(
                productRepository, collectionRepository, categoryRepository,
                imageStorage, imageValidator, redisCache, sellerProductService,
                mongoTemplate, cartService, webSocketService
        );

        String productId = new ObjectId().toHexString();
        String sellerId = "seller-987";
        Product product = new Product();
        product.setId(productId);
        product.setSellerId(sellerId);
        product.setTitle("Bio Fertilizer");
        product.setIsDeleted(false);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        adminProductService.review(productId, false);

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        verify(webSocketService).sendToUser(userCaptor.capture(), eventCaptor.capture(), payloadCaptor.capture());

        assertEquals(sellerId, userCaptor.getValue());
        assertEquals("products:review_status", eventCaptor.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payloadCaptor.getValue();
        assertEquals(productId, data.get("productId"));
        assertEquals("REJECTED", data.get("status"));
        assertEquals("Bio Fertilizer", data.get("title"));
    }

    @Test
    void adminUserBlock_shouldSendAuthSessionRevokedEvent() {
        AdminUserServiceImpl adminUserService = new AdminUserServiceImpl(
                mongoTemplate, userRepository, redisCache, webSocketService
        );

        String userId = "user-abc-123";
        User user = new User();
        user.setId(userId);
        user.setIsBlocked(false);
        user.setRole(UserRole.ROLE_CUSTOMER);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        adminUserService.setBlockedStatus(userId, true);

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        verify(webSocketService).sendToUser(userCaptor.capture(), eventCaptor.capture(), payloadCaptor.capture());

        assertEquals(userId, userCaptor.getValue());
        assertEquals("auth:session_revoked", eventCaptor.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payloadCaptor.getValue();
        assertEquals(userId, data.get("userId"));
        assertEquals("Account has been blocked by administrator", data.get("reason"));
    }

    @Test
    void cartClear_shouldSendCartUpdateToUser() {
        CartServiceImpl cartService = new CartServiceImpl(
                cartRepository, productVariantLookup, userRepository, webSocketService
        );

        String phoneNumber = "9876543210";
        String userId = "user-cart-777";
        User user = new User();
        user.setId(userId);
        user.setPhoneNumber(phoneNumber);
        user.setIsDeleted(false);

        Cart cart = new Cart();
        cart.setId("cart-111");
        cart.setUser(userId);
        cart.setItems(new ArrayList<>());
        cart.setTotalAmount(0.0);
        cart.setFinalAmount(0.0);

        when(userRepository.findByPhoneNumber(phoneNumber)).thenReturn(Optional.of(user));
        when(cartRepository.findByUser(userId)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        cartService.clearCart(phoneNumber);

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        verify(webSocketService).sendToUser(userCaptor.capture(), eventCaptor.capture(), payloadCaptor.capture());

        assertEquals(userId, userCaptor.getValue());
        assertEquals("cart:update", eventCaptor.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payloadCaptor.getValue();
        assertEquals("cart-111", data.get("cartId"));
        assertEquals(0, data.get("totalItems"));
        assertEquals(0, data.get("totalQuantity"));
    }
}
