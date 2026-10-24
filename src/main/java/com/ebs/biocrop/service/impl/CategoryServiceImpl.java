package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.dto.request.CategoryWriteRequest;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.service.CategoryService;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.service.ProductImageVariantService;
import com.ebs.biocrop.service.RedisJsonCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@Service
public class CategoryServiceImpl implements CategoryService {
    private static final Logger log = LoggerFactory.getLogger(CategoryServiceImpl.class);
    private static final Sort CATEGORY_ORDER = Sort.by(Sort.Direction.ASC, "sortOrder")
            .and(Sort.by(Sort.Direction.ASC, "name"));

    private final CategoryRepository categories;
    private final RedisJsonCache redisCache;
    private final GcsImageStorageService imageStorage;
    private final ProductImageVariantService imageValidator;
    private final MongoTemplate mongoTemplate;

    public CategoryServiceImpl(CategoryRepository categories, RedisJsonCache redisCache,
                               GcsImageStorageService imageStorage, ProductImageVariantService imageValidator,
                               MongoTemplate mongoTemplate) {
        this.categories = categories;
        this.redisCache = redisCache;
        this.imageStorage = imageStorage;
        this.imageValidator = imageValidator;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Page<Category> listPublic(String parentId, Integer level, int page, int size) {
        validateLevel(level);
        return categories.findPublicPage(parentId, level, PageRequestSupport.create(page, size, CATEGORY_ORDER));
    }

    @Override
    public Category getPublic(String id) {
        return categories.findById(id)
                .filter(item -> !Boolean.FALSE.equals(item.getIsActive()))
                .filter(item -> item.getLevel() != null && item.getLevel() >= 0 && item.getLevel() <= 2)
                .orElseThrow(() -> new ResourceNotFoundException("Category", "id", id));
    }

    @Override
    public Page<Category> listAdmin(String parentId, Integer level, Boolean active, int page, int size) {
        validateLevel(level);
        return categories.findAdminPage(parentId, level, active, PageRequestSupport.create(page, size, CATEGORY_ORDER));
    }

    @Override
    public Category getAdminById(String id) {
        return findCategory(id);
    }

    @Override
    public Category create(CategoryWriteRequest request) {
        Category category = new Category();
        apply(request, category, null);
        Instant now = Instant.now();
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        Category saved = categories.save(category);
        invalidatePublicCatalogCaches();
        return saved;
    }

    @Override
    public Category update(String id, CategoryWriteRequest request) {
        Category current = findCategory(id);
        Category updated = new Category();
        apply(request, updated, current);
        updated.setId(current.getId());
        updated.setCreatedAt(current.getCreatedAt());
        updated.setUpdatedAt(Instant.now());
        Category saved = categories.save(updated);
        if (!Objects.equals(current.getLevel(), saved.getLevel())) {
            relevelChildren(saved.getId(), saved.getLevel() + 1);
        }
        invalidatePublicCatalogCaches();
        return saved;
    }

    @Override
    public Category deactivate(String id) {
        Category category = findCategory(id);
        if (hasActiveChildren(id)) {
            throw new AppException("Deactivate child categories before deactivating their parent", HttpStatus.CONFLICT);
        }
        category.setIsActive(false);
        category.setUpdatedAt(Instant.now());
        Category saved = categories.save(category);
        invalidatePublicCatalogCaches();
        return saved;
    }

    @Override
    @Transactional
    public List<String> permanentlyDelete(String id) {
        return permanentlyDeleteMany(List.of(id));
    }

    @Override
    @Transactional
    public List<String> permanentlyDeleteMany(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new AppException("At least one category ID is required", HttpStatus.BAD_REQUEST);
        }
        if (ids.size() > 100) {
            throw new AppException("At most 100 categories can be deleted at once", HttpStatus.BAD_REQUEST);
        }

        Set<String> requestedIds = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                throw new AppException("Category IDs cannot be blank", HttpStatus.BAD_REQUEST);
            }
            if (!requestedIds.add(id.trim())) {
                throw new AppException("Duplicate category IDs are not allowed", HttpStatus.BAD_REQUEST);
            }
        }

        List<Category> selected = new ArrayList<>();
        for (String id : requestedIds) selected.add(findCategory(id));

        if (selected.stream().anyMatch(category -> !Boolean.FALSE.equals(category.getIsActive()))) {
            throw new AppException("Deactivate every category before permanently deleting it", HttpStatus.CONFLICT);
        }

        boolean hasExternalChildren = mongoTemplate.exists(
                Query.query(Criteria.where("parentId").in(requestedIds).and("_id").nin(requestedIds)),
                Category.class);
        if (hasExternalChildren) {
            throw new AppException("Categories with child categories cannot be permanently deleted", HttpStatus.CONFLICT);
        }
        boolean hasProducts = mongoTemplate.exists(
                Query.query(Criteria.where("categoryId").in(requestedIds)), Product.class);
        if (hasProducts) {
            throw new AppException("Categories assigned to products cannot be permanently deleted", HttpStatus.CONFLICT);
        }

        var deletion = mongoTemplate.remove(Query.query(Criteria.where("_id").in(requestedIds)), Category.class);
        if (deletion.getDeletedCount() != selected.size()) {
            throw new AppException("One or more categories changed during deletion; reload and retry", HttpStatus.CONFLICT);
        }
        selected.forEach(category -> {
            String categoryId = category.getId();
            String iconObjectName = category.getIconImage();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteStoredIcon(categoryId, iconObjectName);
                }
            });
        });
        invalidatePublicCatalogCaches();
        return List.copyOf(requestedIds);
    }

    @Override
    public Category uploadIcon(String id, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException("A category icon image is required", HttpStatus.BAD_REQUEST);
        }
        Category category = findCategory(id);
        byte[] bytes;
        ProductImageVariantService.Variants validated;
        try {
            bytes = file.getBytes();
            validated = imageValidator.createVariants(bytes);
        } catch (IOException exception) {
            throw new AppException("Could not read category icon image", HttpStatus.BAD_REQUEST);
        }

        String newObjectName = imageStorage.uploadCategoryIcon(
                category.getId(), bytes, validated.extension(), validated.contentType());
        String oldObjectName = category.getIconImage();
        try {
            category.setIconImage(newObjectName);
            category.setUpdatedAt(Instant.now());
            Category saved = categories.save(category);
            invalidatePublicCatalogCaches();
            deleteStoredIcon(category.getId(), oldObjectName);
            return saved;
        } catch (RuntimeException exception) {
            deleteStoredIcon(category.getId(), newObjectName);
            throw exception;
        }
    }

    @Override
    public Category deleteIcon(String id) {
        Category category = findCategory(id);
        String oldObjectName = category.getIconImage();
        category.setIconImage(null);
        category.setUpdatedAt(Instant.now());
        Category saved = categories.save(category);
        invalidatePublicCatalogCaches();
        deleteStoredIcon(category.getId(), oldObjectName);
        return saved;
    }

    private void deleteStoredIcon(String categoryId, String objectName) {
        if (objectName == null || !objectName.startsWith("Categories/" + categoryId + "/icons/")) return;
        try {
            imageStorage.deleteCategoryIcon(categoryId, objectName);
        } catch (RuntimeException exception) {
            log.warn("Could not delete replaced category icon from GCS for category {}", categoryId, exception);
        }
    }

    private void apply(CategoryWriteRequest request, Category candidate, Category current) {
        candidate.setName(request.getName().trim());
        String requestedParent = request.getParentId();
        String parentId = requestedParent == null || requestedParent.isBlank() ? null : requestedParent.trim();
        int level = 0;
        if (parentId != null) {
            Category parent = categories.findById(parentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Parent category", "id", parentId));
            if (Boolean.FALSE.equals(parent.getIsActive())) {
                throw new AppException("Cannot add a child to an inactive category", HttpStatus.CONFLICT);
            }
            if (current != null && isDescendantOrSelf(parentId, current.getId())) {
                throw new AppException("A category cannot be moved below itself or one of its descendants", HttpStatus.BAD_REQUEST);
            }
            if (parent.getLevel() == null || parent.getLevel() < 0 || parent.getLevel() > 2) {
                throw new AppException("Parent category has an invalid level", HttpStatus.CONFLICT);
            }
            level = parent.getLevel() + 1;
            if (level > 2) {
                throw new AppException("Category hierarchy cannot exceed level 2", HttpStatus.BAD_REQUEST);
            }
            if (current != null && subtreeDepth(current.getId()) > 2 - level) {
                throw new AppException("Moving this category would exceed the maximum hierarchy depth of 2", HttpStatus.CONFLICT);
            }
        }
        candidate.setParentId(parentId);
        candidate.setLevel(level);
        if (Boolean.FALSE.equals(request.getIsActive()) && current != null && hasActiveChildren(current.getId())) {
            throw new AppException("Deactivate child categories before deactivating their parent", HttpStatus.CONFLICT);
        }
        String requestedSlug = normalize(request.getSlug());
        String slug = requestedSlug.isBlank() ? slugify(candidate.getName()) : slugify(requestedSlug);
        if (slug.isBlank()) throw new AppException("Category slug cannot be blank", HttpStatus.BAD_REQUEST);
        boolean duplicate = categories.existsSlugUnderParent(parentId, slug, current == null ? null : current.getId());
        if (duplicate) {
            throw new AppException("A category with this slug already exists under the selected parent", HttpStatus.CONFLICT);
        }
        candidate.setSlug(slug);
        candidate.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        candidate.setIsActive(request.getIsActive() == null
                ? current == null || !Boolean.FALSE.equals(current.getIsActive())
                : request.getIsActive());
        candidate.setBannerImage(request.getBannerImage() == null
                ? current == null ? null : current.getBannerImage() : request.getBannerImage());
        candidate.setCataloguePdf(request.getCataloguePdf() == null
                ? current == null ? null : current.getCataloguePdf() : request.getCataloguePdf());
        candidate.setIconImage(request.getIconImage() == null
                ? current == null ? null : current.getIconImage() : request.getIconImage());
        candidate.setIconUrl(request.getIconUrl() == null
                ? current == null ? null : current.getIconUrl() : request.getIconUrl());
        candidate.setBannerTitle(request.getBannerTitle() == null
                ? current == null ? null : current.getBannerTitle() : request.getBannerTitle());
    }

    private Category findCategory(String id) {
        return categories.findById(id).orElseThrow(() -> new ResourceNotFoundException("Category", "id", id));
    }

    private void validateLevel(Integer level) {
        if (level != null && (level < 0 || level > 2)) {
            throw new AppException("Supported category levels are 0, 1, and 2", HttpStatus.BAD_REQUEST);
        }
    }

    private boolean isDescendantOrSelf(String candidateParentId, String categoryId) {
        String cursor = candidateParentId;
        while (cursor != null) {
            if (cursor.equals(categoryId)) return true;
            Category node = categories.findById(cursor).orElse(null);
            cursor = node == null ? null : node.getParentId();
        }
        return false;
    }

    private boolean hasActiveChildren(String parentId) {
        return categories.hasActiveChildren(parentId);
    }

    private int subtreeDepth(String categoryId) {
        int max = 0;
        for (Category child : categories.findChildren(categoryId, 100)) {
            max = Math.max(max, 1 + subtreeDepth(child.getId()));
        }
        return max;
    }

    private void relevelChildren(String parentId, int childLevel) {
        if (childLevel > 2) {
            throw new AppException("Category hierarchy cannot exceed level 2", HttpStatus.CONFLICT);
        }
        for (Category child : categories.findChildren(parentId, 100)) {
            child.setLevel(childLevel);
            child.setUpdatedAt(Instant.now());
            categories.save(child);
            relevelChildren(child.getId(), childLevel + 1);
        }
    }

    private void invalidatePublicCatalogCaches() {
        redisCache.invalidateRegion("public-categories");
        redisCache.invalidateRegion("public-products");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String slugify(String value) {
        return normalize(value).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }
}
