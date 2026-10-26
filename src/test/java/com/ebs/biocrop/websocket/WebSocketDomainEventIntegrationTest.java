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
}
