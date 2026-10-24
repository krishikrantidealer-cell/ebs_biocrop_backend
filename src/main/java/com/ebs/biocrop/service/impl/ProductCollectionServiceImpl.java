package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.CollectionWriteRequest;
import com.ebs.biocrop.dto.request.CollectionPatchRequest;
import com.ebs.biocrop.dto.response.CollectionResponse;
import com.ebs.biocrop.common.pagination.PageRequestSupport;
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
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    public Page<CollectionResponse> listAdmin(Boolean active, int page, int size) {
        Criteria criteria = active == null ? new Criteria()
                : active
                    ? new Criteria().orOperator(Criteria.where("isActive").is(true), Criteria.where("isActive").is(null))
                    : Criteria.where("isActive").is(false);
        var pageable = PageRequestSupport.create(page, size,
                Sort.by(Sort.Direction.ASC, "priority").and(Sort.by(Sort.Direction.ASC, "name")));
        Query query = Query.query(criteria).with(pageable);
        List<ProductCollection> content = mongoTemplate.find(query, ProductCollection.class);
        long total = mongoTemplate.count(Query.query(criteria), ProductCollection.class);
        return new PageImpl<>(content.stream().map(item -> response(item, false)).toList(), pageable, total);
    }

    @Override
    public CollectionResponse getAdminById(String id) {
        return response(find(id), false);
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
    @Transactional
    public CollectionResponse update(String id, CollectionPatchRequest request) {
        if (!request.hasChanges()) throw badRequest("At least one collection field must be provided");
        ProductCollection current = find(id);
        ProductCollection previous = new ProductCollection();
        previous.setSubCollections(current.getSubCollections() == null
                ? new ArrayList<>() : new ArrayList<>(current.getSubCollections()));

        if (request.getName() != null) current.setName(request.getName().trim());
        if (request.getSlug() != null) {
            String slug = slugify(request.getSlug());
            if (slug.isBlank()) throw badRequest("Collection slug cannot be blank");
            if (collections.existsBySlugIgnoreCaseAndIdNot(slug, current.getId())) {
                throw new AppException("A collection with this slug already exists", HttpStatus.CONFLICT);
            }
            current.setSlug(slug);
        }
        if (request.getDescription() != null) current.setDescription(trimToNull(request.getDescription()));
        if (request.getIsActive() != null) current.setIsActive(request.getIsActive());
        if (request.getPriority() != null) current.setPriority(request.getPriority());
        if (request.getBannerTitle() != null) current.setBannerTitle(trimToNull(request.getBannerTitle()));
        if (request.getSubCollections() != null) {
            current.setSubCollections(mapSubCollections(request.getSubCollections(), current));
        }
        current.setUpdatedAt(Instant.now());
        CollectionResponse response = save(current);
        removeAssignmentsForRemovedSubCollections(previous, current);
        return response;
    }

    /** PUT replacement: omitted optional values reset to their documented defaults. */
    @Override
    @Transactional
    public CollectionResponse replace(String id, CollectionWriteRequest request) {
        ProductCollection current = find(id);
        ProductCollection replacement = new ProductCollection();
        replacement.setId(current.getId());
        replacement.setCreatedAt(current.getCreatedAt());
        replacement.setUpdatedAt(Instant.now());
        replacement.setVersion(current.getVersion());
        replacement.setName(request.getName().trim());
        String slug = slugify(request.getSlug() == null || request.getSlug().isBlank()
                ? request.getName() : request.getSlug());
        if (slug.isBlank()) throw badRequest("Collection slug cannot be blank");
        if (collections.existsBySlugIgnoreCaseAndIdNot(slug, current.getId())) {
            throw new AppException("A collection with this slug already exists", HttpStatus.CONFLICT);
        }
        replacement.setSlug(slug);
        replacement.setDescription(trimToNull(request.getDescription()));
        replacement.setIsActive(request.getIsActive() == null || request.getIsActive());
        replacement.setPriority(request.getPriority() == null ? 0 : request.getPriority());
        replacement.setBannerTitle(trimToNull(request.getBannerTitle()));
        replacement.setSubCollections(request.getSubCollections() == null
                ? new ArrayList<>() : mapSubCollections(request.getSubCollections(), current));
        CollectionResponse response = save(replacement);
        removeAssignmentsForRemovedSubCollections(current, replacement);
        return response;
    }

    @Override
    public CollectionResponse deactivate(String id) {
        ProductCollection collection = find(id);
        collection.setIsActive(false);
        collection.setUpdatedAt(Instant.now());
        return save(collection);
    }

    @Override
    @Transactional
    public List<String> deleteSubCollections(String collectionId, List<String> subCollectionIds) {
        if (subCollectionIds == null || subCollectionIds.isEmpty()) {
            throw badRequest("At least one subcollection ID is required");
        }
        if (subCollectionIds.size() > 100) {
            throw badRequest("At most 100 subcollections can be deleted at once");
        }
        ProductCollection collection = find(collectionId);
        Set<String> requested = new LinkedHashSet<>();
        for (String id : subCollectionIds) {
            if (!ObjectId.isValid(id)) throw badRequest("Subcollection IDs must be valid MongoDB ObjectIds");
            String normalized = new ObjectId(id).toHexString();
            if (!requested.add(normalized)) throw badRequest("Duplicate subcollection IDs are not allowed");
        }

        List<ProductCollection.SubCollection> existing = collection.getSubCollections() == null
                ? List.of() : collection.getSubCollections();
        Set<String> found = existing.stream().filter(sub -> sub.getId() != null)
                .map(sub -> sub.getId().toHexString()).collect(java.util.stream.Collectors.toSet());
        for (String id : requested) {
            if (!found.contains(id)) throw new ResourceNotFoundException("Subcollection", "id", id);
        }

        collection.setSubCollections(existing.stream()
                .filter(sub -> sub.getId() == null || !requested.contains(sub.getId().toHexString()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        collection.setUpdatedAt(Instant.now());
        collections.save(collection);
        removeProductAssignments(requested);
        redisCache.invalidateRegion("public-collections");
        redisCache.invalidateRegion("public-products");
        return List.copyOf(requested);
    }

    private void removeAssignmentsForRemovedSubCollections(ProductCollection previous, ProductCollection updated) {
        Set<String> previousIds = previous.getSubCollections() == null ? new LinkedHashSet<>()
                : previous.getSubCollections().stream().filter(sub -> sub.getId() != null)
                    .map(sub -> sub.getId().toHexString()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> updatedIds = updated.getSubCollections() == null ? Set.of()
                : updated.getSubCollections().stream().filter(sub -> sub.getId() != null)
                    .map(sub -> sub.getId().toHexString()).collect(java.util.stream.Collectors.toSet());
        previousIds.removeAll(updatedIds);
        if (!previousIds.isEmpty()) {
            removeProductAssignments(previousIds);
            redisCache.invalidateRegion("public-products");
        }
    }

    private void removeProductAssignments(Set<String> subCollectionIds) {
        mongoTemplate.updateMulti(Query.query(Criteria.where("assignedCollections").in(subCollectionIds)),
                new Update().pullAll("assignedCollections", subCollectionIds.toArray()), Product.class);
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
