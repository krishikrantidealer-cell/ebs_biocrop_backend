package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.response.WishlistResponse;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.entity.Wishlist;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.ProductVariantLookup;
import com.ebs.biocrop.repository.WishlistRepository;
import com.ebs.biocrop.service.WishlistService;
import jakarta.annotation.PostConstruct;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class WishlistServiceImpl implements WishlistService {

    private static final String ACTIVE_STATUS = "ACTIVE";

    private final WishlistRepository wishlistRepository;
    private final ProductRepository productRepository;
    private final ProductVariantLookup productVariantLookup;
    private final MongoTemplate mongoTemplate;

    public WishlistServiceImpl(
            WishlistRepository wishlistRepository,
            ProductRepository productRepository,
            ProductVariantLookup productVariantLookup,
            MongoTemplate mongoTemplate) {
        this.wishlistRepository = wishlistRepository;
        this.productRepository = productRepository;
        this.productVariantLookup = productVariantLookup;
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    void ensureWishlistUserIndex() {
        mongoTemplate.indexOps(Wishlist.class).ensureIndex(
                new Index().on("user", Sort.Direction.ASC).unique().named("uniq_wishlist_user"));
    }

    @Override
    public WishlistResponse getWishlist(String userId) {
        return wishlistRepository.findByUser(userId)
                .map(this::toResponse)
                .orElseGet(() -> new WishlistResponse(List.of(), List.of(), null, null));
    }

    @Override
    public WishlistResponse addVariant(String userId, String variantId) {
        String normalizedVariantId = normalizeVariantId(variantId);
        VariantSelection selection = requireActiveVariant(normalizedVariantId);
        if (!isActive(selection.product())) {
            throw new ResourceNotFoundException("Active product variant", "id", normalizedVariantId);
        }
        LocalDateTime now = LocalDateTime.now();
        Query query = Query.query(Criteria.where("user").is(userId));
        Update update = new Update()
                .setOnInsert("user", userId)
                .setOnInsert("createdAt", now)
                .set("updatedAt", now)
                .addToSet("variantIds", normalizedVariantId)
                .set("variantProductIds." + normalizedVariantId, selection.product().getId())
                .pull("products", selection.product().getId());

        Wishlist wishlist;
        try {
            wishlist = mongoTemplate.findAndModify(
                    query, update,
                    FindAndModifyOptions.options().upsert(true).returnNew(true),
                    Wishlist.class);
        } catch (DuplicateKeyException concurrentFirstInsert) {
            wishlist = mongoTemplate.findAndModify(
                    query, update,
                    FindAndModifyOptions.options().returnNew(true),
                    Wishlist.class);
            if (wishlist == null) {
                throw concurrentFirstInsert;
            }
        }
        if (wishlist == null) {
            throw new IllegalStateException("Wishlist update did not return a document.");
        }
        return toResponse(wishlist);
    }

    @Override
    public WishlistResponse removeVariant(String userId, String variantId) {
        String normalizedVariantId = normalizeVariantId(variantId);
        Wishlist wishlist = mongoTemplate.findAndModify(
                Query.query(Criteria.where("user").is(userId)),
                new Update().pull("variantIds", normalizedVariantId)
                        .unset("variantProductIds." + normalizedVariantId)
                        .set("updatedAt", LocalDateTime.now()),
                FindAndModifyOptions.options().returnNew(true),
                Wishlist.class);
        return wishlist != null
                ? toResponse(wishlist)
                : new WishlistResponse(List.of(), List.of(), null, null);
    }

    @Override
    public WishlistResponse clearWishlist(String userId) {
        Wishlist wishlist = mongoTemplate.findAndModify(
                Query.query(Criteria.where("user").is(userId)),
                new Update().set("variantIds", new ArrayList<>())
                        .set("variantProductIds", new HashMap<>())
                        .unset("products")
                        .set("updatedAt", LocalDateTime.now()),
                FindAndModifyOptions.options().returnNew(true),
                Wishlist.class);
        return wishlist != null
                ? toResponse(wishlist)
                : new WishlistResponse(List.of(), List.of(), null, null);
    }

    private VariantSelection requireActiveVariant(String variantId) {
        List<VariantSelection> matches = findSelections(List.of(variantId));
        if (matches.isEmpty()) {
            throw new ResourceNotFoundException("Product variant", "id", variantId);
        }
        if (matches.size() > 1) {
            throw new AppException("Variant ID is assigned to more than one product.", HttpStatus.CONFLICT);
        }
        VariantSelection selection = matches.getFirst();
        if (!isActive(selection.product())) {
            throw new ResourceNotFoundException("Active product variant", "id", variantId);
        }
        return selection;
    }

    private String normalizeVariantId(String variantId) {
        if (variantId == null || !ObjectId.isValid(variantId.trim())) {
            throw new AppException("Variant ID must be a valid MongoDB ObjectId.", HttpStatus.BAD_REQUEST);
        }
        return new ObjectId(variantId.trim()).toHexString();
    }

    private WishlistResponse toResponse(Wishlist wishlist) {
        wishlist = migrateLegacyProductEntries(wishlist);
        List<String> variantIds = wishlist.getVariantIds() == null ? List.of() : wishlist.getVariantIds();
        if (variantIds.isEmpty()) {
            return new WishlistResponse(List.of(), wishlist.getLegacyProductIds(), wishlist.getCreatedAt(), wishlist.getUpdatedAt());
        }

        List<String> validIds = variantIds.stream()
                .filter(id -> id != null && ObjectId.isValid(id))
                .map(id -> new ObjectId(id).toHexString())
                .distinct()
                .toList();
        Map<String, VariantSelection> selectionsById = new HashMap<>();
        Map<String, String> productIdsByVariant = wishlist.getVariantProductIds() == null
                ? Map.of() : wishlist.getVariantProductIds();
        List<String> mappedProductIds = productIdsByVariant.values().stream()
                .filter(id -> id != null && ObjectId.isValid(id))
                .map(id -> new ObjectId(id).toHexString())
                .distinct().toList();
        Map<String, Product> productsById = new HashMap<>();
        productRepository.findAllById(mappedProductIds).forEach(product -> productsById.put(product.getId(), product));
        for (String variantId : validIds) {
            String productId = productIdsByVariant.get(variantId);
            if (productId == null || !ObjectId.isValid(productId)) continue;
            Product product = productsById.get(new ObjectId(productId).toHexString());
            if (product == null || product.getVariants() == null) continue;
            product.getVariants().stream()
                    .filter(variant -> variantId.equalsIgnoreCase(variant.getId()))
                    .findFirst()
                    .ifPresent(variant -> selectionsById.put(variantId, new VariantSelection(product, variant)));
        }
        List<String> unresolvedVariantIds = validIds.stream()
                .filter(id -> !selectionsById.containsKey(id))
                .toList();
        for (VariantSelection selection : findSelections(unresolvedVariantIds)) {
            String id = selection.variant().getId().toLowerCase();
            if (selectionsById.putIfAbsent(id, selection) != null
                    && !selection.product().getId().equals(selectionsById.get(id).product().getId())) {
                throw new AppException("A variant ID is assigned to more than one product.", HttpStatus.CONFLICT);
            }
        }

        List<WishlistResponse.Item> items = variantIds.stream()
                .filter(id -> id != null && ObjectId.isValid(id))
                .map(id -> selectionsById.get(new ObjectId(id).toHexString()))
                .filter(selection -> selection != null && isActive(selection.product()))
                .map(selection -> new WishlistResponse.Item(
                        selection.product().getId(),
                        selection.variant().getId(),
                        selection.product().getTitle(),
                        selection.product().getBrandName(),
                        selection.product().getThumbnail(),
                        selection.variant()))
                .toList();
        return new WishlistResponse(items, wishlist.getLegacyProductIds(), wishlist.getCreatedAt(), wishlist.getUpdatedAt());
    }

    private Wishlist migrateLegacyProductEntries(Wishlist wishlist) {
        List<String> legacyProductIds = wishlist.getLegacyProductIds();
        if (legacyProductIds == null || legacyProductIds.isEmpty()) {
            return wishlist;
        }

        List<String> validProductIds = legacyProductIds.stream()
                .filter(id -> id != null && ObjectId.isValid(id))
                .map(id -> new ObjectId(id).toHexString())
                .distinct()
                .toList();
        Map<String, Product> productsById = new HashMap<>();
        productRepository.findAllById(validProductIds).forEach(product -> productsById.put(product.getId(), product));

        List<String> migratedProductIds = new ArrayList<>();
        List<String> migratedVariantIds = new ArrayList<>();
        Map<String, String> migratedVariantProductIds = new HashMap<>();
        for (String productId : validProductIds) {
            Product product = productsById.get(productId);
            if (product == null || product.getVariants() == null || product.getVariants().isEmpty()) {
                continue;
            }
            List<ProductVariant> defaults = product.getVariants().stream()
                    .filter(variant -> Boolean.TRUE.equals(variant.getIsDefault()))
                    .toList();
            ProductVariant selected = defaults.size() == 1
                    ? defaults.getFirst()
                    : defaults.isEmpty() && product.getVariants().size() == 1
                    ? product.getVariants().getFirst()
                    : null;
            if (selected != null && selected.getId() != null && ObjectId.isValid(selected.getId())) {
                migratedProductIds.add(productId);
                String variantId = new ObjectId(selected.getId()).toHexString();
                migratedVariantIds.add(variantId);
                migratedVariantProductIds.put(variantId, productId);
            }
        }

        if (migratedProductIds.isEmpty()) {
            return wishlist;
        }
        Update update = new Update()
                .addToSet("variantIds").each(migratedVariantIds.toArray())
                .pullAll("products", migratedProductIds.toArray());
        migratedVariantProductIds.forEach((variantId, productId) ->
                update.set("variantProductIds." + variantId, productId));
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(wishlist.getId())
                        .and("products").in(migratedProductIds)),
                update,
                Wishlist.class);
        return wishlistRepository.findById(wishlist.getId()).orElse(wishlist);
    }

    private List<VariantSelection> findSelections(List<String> variantIds) {
        List<String> normalizedIds = variantIds.stream()
                .filter(id -> id != null && ObjectId.isValid(id))
                .map(id -> new ObjectId(id).toHexString())
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return List.of();
        }

        List<VariantSelection> selections = new ArrayList<>();
        for (Product product : productVariantLookup.findProductsContainingVariants(normalizedIds)) {
            if (product.getVariants() == null) {
                continue;
            }
            for (ProductVariant variant : product.getVariants()) {
                if (variant.getId() != null && normalizedIds.stream().anyMatch(id -> id.equalsIgnoreCase(variant.getId()))) {
                    selections.add(new VariantSelection(product, variant));
                }
            }
        }
        return selections;
    }

    private boolean isActive(Product product) {
        String status = product.getStatus();
        return status == null || ACTIVE_STATUS.equalsIgnoreCase(status.trim());
    }

    private record VariantSelection(Product product, ProductVariant variant) {}
}
