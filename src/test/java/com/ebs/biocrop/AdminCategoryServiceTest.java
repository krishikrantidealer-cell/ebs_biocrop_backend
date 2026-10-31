package com.ebs.biocrop;

import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.service.ProductImageVariantService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.service.impl.CategoryServiceImpl;
import com.ebs.biocrop.websocket.WebSocketService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminCategoryServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private GcsImageStorageService imageStorage;
    @Mock private ProductImageVariantService imageValidator;
    @Mock private RedisJsonCache redisCache;
    @Mock private WebSocketService webSocketService;

    private CategoryServiceImpl categoryService;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryServiceImpl(
                categoryRepository, redisCache, imageStorage, imageValidator,
                mongoTemplate, webSocketService);
    }

    @Test
    @DisplayName("permanentlyDelete cascades child categories, unsets product categoryId, and removes documents")
    void testPermanentlyDeleteWithCascadingChildren() {
        String rootId = new ObjectId().toHexString();
        String childId = new ObjectId().toHexString();

        Category root = new Category();
        root.setId(rootId);
        root.setName("Crop Nutrition");
        root.setIsActive(true); // Active category can be permanently deleted directly!

        Category child = new Category();
        child.setId(childId);
        child.setName("Bio Fertilizers");
        child.setParentId(rootId);
        child.setIsActive(true);

        when(categoryRepository.findById(rootId)).thenReturn(Optional.of(root));
        when(categoryRepository.findById(childId)).thenReturn(Optional.of(child));
        // Mock finding children for root
        when(mongoTemplate.find(any(Query.class), eq(Category.class)))
                .thenReturn(List.of(child)) // First level returns child
                .thenReturn(List.of());     // Next level returns empty

        List<String> deleted = categoryService.permanentlyDelete(rootId);

        assertEquals(2, deleted.size());
        assertTrue(deleted.contains(rootId));
        assertTrue(deleted.contains(childId));

        // Verify product categoryId unset
        verify(mongoTemplate).updateMulti(any(Query.class), any(Update.class), eq(Product.class));
        // Verify categories removed
        verify(mongoTemplate).remove(any(Query.class), eq(Category.class));
        // Verify WS delete broadcast for each deleted ID
        verify(webSocketService).broadcast(eq("categories:delete"), eq(Map.of("categoryId", rootId)));
        verify(webSocketService).broadcast(eq("categories:delete"), eq(Map.of("categoryId", childId)));
    }
}
