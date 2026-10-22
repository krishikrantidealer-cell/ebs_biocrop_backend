package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.CollectionWriteRequest;
import com.ebs.biocrop.dto.response.CollectionResponse;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductCollection;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.ProductCollectionRepository;
import com.ebs.biocrop.service.ProductCollectionService;
import com.ebs.biocrop.service.RedisJsonCache;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ProductCollectionServiceImpl implements ProductCollectionService {
    private final ProductCollectionRepository collections;
    private final MongoTemplate mongoTemplate;
    private final RedisJsonCache redisCache;

    public ProductCollectionServiceImpl(ProductCollectionRepository collections, MongoTemplate mongoTemplate,
                                        RedisJsonCache redisCache) {
        this.collections = collections;
        this.mongoTemplate = mongoTemplate;
        this.redisCache = redisCache;
    }

    @Override
    public List<CollectionResponse> listPublic() {
        return collections.findByIsActiveTrueOrderByPriorityAscNameAsc().stream().map(item -> response(item, true)).toList();
    }

    @Override
    public CollectionResponse getPublicBySlug(String slug) {
        String normalized = slugify(slug);
        ProductCollection collection = collections.findBySlugIgnoreCaseAndIsActiveTrue(normalized)
                .orElseThrow(() -> new ResourceNotFoundException("Collection", "slug", slug));
        return response(collection, true);
    }

    @Override
    public CollectionResponse create(CollectionWriteRequest request) {
        ProductCollection collection = new ProductCollection();
        apply(request, collection, null);
        Instant now = Instant.now();
        collection.setCreatedAt(now);
        collection.setUpdatedAt(now);
        return save(collection);
    }

    @Override
    public CollectionResponse update(String id, CollectionWriteRequest request) {
        ProductCollection current = find(id);
        ProductCollection updated = new ProductCollection();
        apply(request, updated, current);
        updated.setId(current.getId());
        updated.setCreatedAt(current.getCreatedAt());
        updated.setUpdatedAt(Instant.now());
        updated.setVersion(current.getVersion());
        return save(updated);
    }

    @Override
    public CollectionResponse deactivate(String id) {
        ProductCollection collection = find(id);
        collection.setIsActive(false);
        collection.setUpdatedAt(Instant.now());
        return save(collection);
    }

    private void apply(CollectionWriteRequest request, ProductCollection target, ProductCollection current) {
        target.setName(request.getName().trim());
        String requestedSlug = request.getSlug();
        String slugSource = requestedSlug == null
                ? current == null ? target.getName() : current.getSlug()
                : requestedSlug;
        String slug = slugify(slugSource);
        if (slug.isBlank()) throw badRequest("Collection slug cannot be blank");
        boolean duplicate = current == null ? collections.existsBySlugIgnoreCase(slug)
                : collections.existsBySlugIgnoreCaseAndIdNot(slug, current.getId());
        if (duplicate) throw new AppException("A collection with this slug already exists", HttpStatus.CONFLICT);
        target.setSlug(slug);
        target.setDescription(request.getDescription() == null && current != null
                ? current.getDescription() : trimToNull(request.getDescription()));
        target.setIsActive(request.getIsActive() == null
                ? current == null || !Boolean.FALSE.equals(current.getIsActive()) : request.getIsActive());
        target.setPriority(request.getPriority() == null ? current == null ? 0 : current.getPriority() : request.getPriority());
        target.setBannerTitle(trimToNull(request.getBannerTitle() == null && current != null
                ? current.getBannerTitle() : request.getBannerTitle()));
        target.setSubCollections(request.getSubCollections() == null
                ? current == null ? new ArrayList<>() : new ArrayList<>(current.getSubCollections())
                : mapSubCollections(request.getSubCollections(), current));
    }

    private List<ProductCollection.SubCollection> mapSubCollections(
            List<CollectionWriteRequest.SubCollectionInput> inputs, ProductCollection current) {
        List<ProductCollection.SubCollection> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> slugs = new LinkedHashSet<>();
        for (CollectionWriteRequest.SubCollectionInput input : inputs) {
            ProductCollection.SubCollection old = null;
            String id = input.getId() == null || input.getId().isBlank() ? null : input.getId().trim();
            if (id != null) {
                if (!ObjectId.isValid(id)) throw badRequest("Subcollection IDs must be valid MongoDB ObjectIds");
                String requestedId = id;
                old = current == null || current.getSubCollections() == null ? null
                        : current.getSubCollections().stream()
                        .filter(value -> value.getId() != null && requestedId.equalsIgnoreCase(value.getId().toHexString()))
                        .findFirst().orElse(null);
                if (old == null) throw badRequest("A subcollection ID must belong to the collection being updated");
                id = old.getId().toHexString();
            } else {
                id = new ObjectId().toHexString();
            }
            String slug = slugify(input.getSlug() == null || input.getSlug().isBlank() ? input.getName() : input.getSlug());
            if (slug.isBlank()) throw badRequest("Subcollection slug cannot be blank");
            if (!ids.add(id.toLowerCase(Locale.ROOT))) throw badRequest("Duplicate subcollection IDs are not allowed");
            if (!slugs.add(slug)) throw new AppException("Subcollection slugs must be unique within a collection", HttpStatus.CONFLICT);
            ProductCollection.SubCollection sub = new ProductCollection.SubCollection();
            sub.setId(new ObjectId(id));
            sub.setName(input.getName().trim());
            sub.setSlug(slug);
            sub.setIsActive(input.getIsActive() == null ? old == null || !Boolean.FALSE.equals(old.getIsActive()) : input.getIsActive());
            sub.setImage(trimToNull(input.getImage()));
            result.add(sub);
        }
        return result;
    }

    private CollectionResponse save(ProductCollection collection) {
        try {
            ProductCollection saved = collections.save(collection);
            redisCache.invalidateRegion("public-collections");
            return response(saved, false);
        } catch (DuplicateKeyException duplicate) {
            throw new AppException("A collection with this slug already exists", HttpStatus.CONFLICT);
        }
    }

    private CollectionResponse response(ProductCollection collection, boolean publicView) {
        long count = countMatchingProducts(tokens(collection.getId(), collection.getName(), collection.getSlug(), collection.getSubCollections()));
        return CollectionResponse.from(collection, count, publicView, sub ->
                countMatchingProducts(tokens(sub.getId() == null ? null : sub.getId().toHexString(),
                        sub.getName(), sub.getSlug(), List.of())));
    }

    private List<String> tokens(String id, String name, String slug, List<ProductCollection.SubCollection> children) {
        Set<String> values = new LinkedHashSet<>();
        addToken(values, id); addToken(values, name); addToken(values, slug);
        if (children != null) children.stream().filter(child -> !Boolean.FALSE.equals(child.getIsActive()))
                .forEach(child -> {
                    if (child.getId() != null) addToken(values, child.getId().toHexString());
                    addToken(values, child.getName());
                    addToken(values, child.getSlug());
                });
        return List.copyOf(values);
    }

    private void addToken(Set<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value.trim());
    }

    private long countMatchingProducts(List<String> values) {
        if (values.isEmpty()) return 0;
        Criteria[] matches = values.stream().map(value -> Criteria.where("assignedCollections")
                .regex("^" + Pattern.quote(value) + "$", "i")).toArray(Criteria[]::new);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("status").regex("^ACTIVE$", "i"),
                Criteria.where("isAvailable").is(true),
                new Criteria().orOperator(matches)));
        return mongoTemplate.count(query, Product.class);
    }

    private ProductCollection find(String id) {
        if (!ObjectId.isValid(id)) throw new ResourceNotFoundException("Collection", "id", id);
        return collections.findById(id).orElseThrow(() -> new ResourceNotFoundException("Collection", "id", id));
    }

    private String slugify(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AppException badRequest(String message) { return new AppException(message, HttpStatus.BAD_REQUEST); }
}
