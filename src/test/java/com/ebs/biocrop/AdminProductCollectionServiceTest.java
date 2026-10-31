package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.CollectionWriteRequest;
import com.ebs.biocrop.dto.response.CollectionResponse;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductCollection;
import com.ebs.biocrop.repository.ProductCollectionRepository;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.service.impl.ProductCollectionServiceImpl;
import com.ebs.biocrop.websocket.WebSocketService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminProductCollectionServiceTest {

    @Mock private ProductCollectionRepository collectionRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private RedisJsonCache redisCache;
    @Mock private WebSocketService webSocketService;

    private ProductCollectionServiceImpl collectionService;

    @BeforeEach
    void setUp() {
        collectionService = new ProductCollectionServiceImpl(
                collectionRepository, mongoTemplate, redisCache, webSocketService);
    }

    private ProductCollection createSampleCollection(String collectionId) {
        ProductCollection collection = new ProductCollection();
        collection.setId(collectionId);
        collection.setName("Organic Fertilizers");
        collection.setSlug("organic-fertilizers");
        collection.setIsActive(true);

        ProductCollection.SubCollection sub1 = new ProductCollection.SubCollection();
        sub1.setId(new ObjectId());
        sub1.setName("Bio NPK");
        sub1.setSlug("bio-npk");
        sub1.setIsActive(true);

        ProductCollection.SubCollection sub2 = new ProductCollection.SubCollection();
        sub2.setId(new ObjectId());
        sub2.setName("Neem Cake");
        sub2.setSlug("neem-cake");
        sub2.setIsActive(true);

        collection.setSubCollections(new ArrayList<>(List.of(sub1, sub2)));
        return collection;
    }

    @Test
    @DisplayName("permanentlyDeleteMany permanently removes collection documents, cleans up product assignments, and broadcasts WS delete")
    void testPermanentlyDeleteMany() {
        String collectionId = new ObjectId().toHexString();
        ProductCollection collection = createSampleCollection(collectionId);
        when(collectionRepository.findById(collectionId)).thenReturn(Optional.of(collection));

        List<String> deleted = collectionService.permanentlyDeleteMany(List.of(collectionId));

        assertEquals(1, deleted.size());
        assertEquals(collectionId, deleted.get(0));
        // Verify product assignments removed for collection ID and both subcollection IDs
        verify(mongoTemplate).updateMulti(any(Query.class), any(Update.class), eq(Product.class));
        verify(collectionRepository).delete(collection);
        verify(redisCache).invalidateRegion("public-collections");
        verify(redisCache).invalidateRegion("public-products");
        verify(webSocketService).broadcast(eq("collections:delete"), any(Map.class));
    }

    @Test
    @DisplayName("deleteSubCollections removes subcollection, purges product assignments, and broadcasts update")
    void testDeleteSubCollections() {
        String collectionId = new ObjectId().toHexString();
        ProductCollection collection = createSampleCollection(collectionId);
        String subToDeleteId = collection.getSubCollections().get(0).getId().toHexString();

        when(collectionRepository.findById(collectionId)).thenReturn(Optional.of(collection));
        when(collectionRepository.save(any(ProductCollection.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<String> deleted = collectionService.deleteSubCollections(collectionId, List.of(subToDeleteId));

        assertEquals(1, deleted.size());
        assertEquals(subToDeleteId, deleted.get(0));
        assertEquals(1, collection.getSubCollections().size());
        verify(mongoTemplate).updateMulti(any(Query.class), any(Update.class), eq(Product.class));
        verify(redisCache).invalidateRegion("public-collections");
        verify(redisCache).invalidateRegion("public-products");
        verify(webSocketService).broadcast(eq("collections:update"), any(Map.class));
    }

    @Test
    @DisplayName("replace gracefully handles newly added subcollections with frontend-generated or null IDs")
    void testReplaceWithNewSubCollections() {
        String collectionId = new ObjectId().toHexString();
        ProductCollection collection = createSampleCollection(collectionId);
        when(collectionRepository.findById(collectionId)).thenReturn(Optional.of(collection));
        when(collectionRepository.existsBySlugIgnoreCaseAndIdNot(anyString(), anyString())).thenReturn(false);
        when(collectionRepository.save(any(ProductCollection.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CollectionWriteRequest request = new CollectionWriteRequest();
        request.setName("Organic Fertilizers Updated");
        request.setSlug("organic-fertilizers-updated");

        CollectionWriteRequest.SubCollectionInput existingSub = new CollectionWriteRequest.SubCollectionInput();
        existingSub.setId(collection.getSubCollections().get(0).getId().toHexString());
        existingSub.setName("Bio NPK");

        CollectionWriteRequest.SubCollectionInput brandNewSub = new CollectionWriteRequest.SubCollectionInput();
        brandNewSub.setId("sub-temporary-frontend-id"); // Non-Mongo hex string from client
        brandNewSub.setName("Liquid Humic");

        request.setSubCollections(List.of(existingSub, brandNewSub));

        CollectionResponse response = collectionService.replace(collectionId, request);

        assertNotNull(response);
        assertEquals(2, response.subCollections().size());
        // Verify brand new sub got assigned a valid 24-hex Mongo ObjectId without crashing
        assertNotNull(response.subCollections().get(1).id());
        assertTrue(ObjectId.isValid(response.subCollections().get(1).id()));
    }

    @Test
    @DisplayName("addSubCollection adds a new subcollection with generated ObjectId to collection and saves")
    void testAddSubCollection() {
        String collectionId = new ObjectId().toHexString();
        ProductCollection collection = createSampleCollection(collectionId);
        when(collectionRepository.findById(collectionId)).thenReturn(Optional.of(collection));
        when(collectionRepository.save(any(ProductCollection.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CollectionWriteRequest.SubCollectionInput input = new CollectionWriteRequest.SubCollectionInput();
        input.setName("Liquid Seaweed");
        input.setSlug("liquid-seaweed");
        input.setIsActive(true);

        CollectionResponse response = collectionService.addSubCollection(collectionId, input);

        assertNotNull(response);
        assertEquals(3, response.subCollections().size());
        assertTrue(response.subCollections().stream().anyMatch(s -> "Liquid Seaweed".equals(s.name())));
        verify(collectionRepository).save(collection);
        verify(redisCache).invalidateRegion("public-collections");
        verify(webSocketService).broadcast(eq("collections:update"), any(Map.class));
    }
}
