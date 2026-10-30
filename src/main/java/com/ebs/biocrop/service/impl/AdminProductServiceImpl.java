package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.websocket.WebSocketService;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.ProductCollection;
import com.ebs.biocrop.dto.request.ProductCollectionAssignmentRequest;
import com.ebs.biocrop.entity.ProductDimensions;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.ProductCollectionRepository;
import com.ebs.biocrop.service.AdminProductService;
import com.ebs.biocrop.service.SellerProductService;
import com.ebs.biocrop.service.CartService;
import com.ebs.biocrop.dto.request.AdminProductWriteRequest;
import com.ebs.biocrop.dto.request.AdminProductPatchRequest;
import com.ebs.biocrop.dto.request.AdminProductUpdateRequest;
import com.ebs.biocrop.dto.request.SellerProductWriteRequest;
import com.ebs.biocrop.dto.request.AdminProductVariantPatchRequest;
import com.ebs.biocrop.dto.request.AdminProductVariantWriteRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.multipart.MultipartFile;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.service.ProductImageVariantService;
import com.ebs.biocrop.service.RedisJsonCache;
import org.bson.types.ObjectId;
import com.ebs.biocrop.entity.ProductImage;


@Service
public class AdminProductServiceImpl implements AdminProductService {
    private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final int MAX_PRODUCT_IMAGES = 6;
    private final ProductRepository products;
    private final ProductCollectionRepository collections;
    private final CategoryRepository categories;
    private final GcsImageStorageService imageStorage;
    private final ProductImageVariantService imageVariants;
    private final RedisJsonCache redisCache;
    private final SellerProductService sellerProducts;
    private final MongoTemplate mongoTemplate;
    private final CartService cartService;
    private final WebSocketService webSocketService;

    public AdminProductServiceImpl(
            ProductRepository products,
            ProductCollectionRepository collections,
            CategoryRepository categories,
            GcsImageStorageService imageStorage,
            ProductImageVariantService imageVariants,
            RedisJsonCache redisCache, SellerProductService sellerProducts, MongoTemplate mongoTemplate, CartService cartService,
            WebSocketService webSocketService) {
        this.products = products;
        this.collections = collections;
        this.categories = categories;
        this.imageStorage = imageStorage;
        this.imageVariants = imageVariants;
        this.redisCache = redisCache;
        this.sellerProducts = sellerProducts;
        this.mongoTemplate = mongoTemplate;
        this.cartService = cartService;
        this.webSocketService = webSocketService;
    }

    @Override
    public Page<Product> list(String status, String sellerId, String categoryId, String query, Boolean deleted, int page, int size) {
        return list(status, sellerId, categoryId, query, deleted, null, page, size);
    }

    @Override
    public Page<Product> list(String status, String sellerId, String categoryId, String query, Boolean deleted, Boolean available, int page, int size) {
        String normalized = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        if (normalized != null && !ALLOWED_STATUSES.contains(normalized)) {
            throw new AppException("Unsupported product status filter", HttpStatus.BAD_REQUEST);
        }
        Pageable pageable = PageRequestSupport.create(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        List<Criteria> filters = new ArrayList<>();
        if (deleted != null) filters.add(Criteria.where("isDeleted").is(deleted));
        else filters.add(new Criteria().orOperator(Criteria.where("isDeleted").is(false), Criteria.where("isDeleted").exists(false), Criteria.where("isDeleted").is(null)));
        if (normalized != null) filters.add(Criteria.where("status").regex("^" + normalized + "$", "i"));
        if (available != null) filters.add(Criteria.where("isAvailable").is(available));
        if (sellerId != null && !sellerId.isBlank()) filters.add(Criteria.where("sellerId").is(sellerId.trim()));
        if (categoryId != null && !categoryId.isBlank()) filters.add(Criteria.where("categoryId").is(categoryId.trim()));
        if (query != null && !query.isBlank()) {
            if (query.trim().length() > 100) throw new AppException("Search query must be at most 100 characters", HttpStatus.BAD_REQUEST);
            String escaped = java.util.regex.Pattern.quote(query.trim());
            filters.add(new Criteria().orOperator(
                    Criteria.where("title").regex(escaped, "i"),
                    Criteria.where("sku").regex(escaped, "i"),
                    Criteria.where("productCode").regex(escaped, "i"),
                    Criteria.where("technicalName").regex(escaped, "i")
            ));
        }
        Query querySpec = new Query(filters.isEmpty() ? new Criteria() : new Criteria().andOperator(filters.toArray(Criteria[]::new)))
                .with(pageable);
        List<Product> content = mongoTemplate.find(querySpec, Product.class);
        long total = mongoTemplate.count(Query.of(querySpec).limit(-1).skip(-1), Product.class);
        return new org.springframework.data.domain.PageImpl<>(content, pageable, total);
    }

    @Override public Product getById(String id) { return findProduct(id); }

    @Override public Product create(String adminId, AdminProductWriteRequest request) {
        User seller = mongoTemplate.findById(request.getSellerId(), User.class);
        if (seller == null || seller.getRole() != com.ebs.biocrop.entity.enums.UserRole.ROLE_SELLER
                || Boolean.TRUE.equals(seller.getIsDeleted())) throw new ResourceNotFoundException("Seller", "id", request.getSellerId());
        if (Boolean.TRUE.equals(seller.getIsBlocked())) throw new AppException("A blocked seller cannot receive new product listings", HttpStatus.CONFLICT);
        return sellerProducts.create(request.getSellerId(), request);
    }

    @Override
    public Product update(String id, AdminProductUpdateRequest request) {
        if (request == null || !request.hasChanges()) {
            throw new AppException("At least one product field must be provided", HttpStatus.BAD_REQUEST);
        }
        Product current = findProduct(id);
        if (Boolean.TRUE.equals(current.getIsDeleted())) {
            throw new AppException("Deleted products must be restored before editing", HttpStatus.CONFLICT);
        }

        // Ownership validation
        if (request.getSellerId() != null && !request.getSellerId().isBlank()) {
            if (current.getSellerId() != null && !current.getSellerId().equals(request.getSellerId().trim())) {
                throw new AppException("Product ownership cannot be changed", HttpStatus.CONFLICT);
            }
        }

        // SKU uniqueness validation
        if (request.getSku() != null && !request.getSku().isBlank()) {
            String requestedSku = request.getSku().trim();
            if (!requestedSku.equalsIgnoreCase(current.getSku()) && products.existsBySkuIgnoreCase(requestedSku)) {
                throw new AppException("SKU is already in use", HttpStatus.CONFLICT);
            }
            current.setSku(requestedSku);
        }

        // Apply provided non-null fields
        if (request.getHsnCode() != null) current.setHsnCode(request.getHsnCode().trim());
        if (request.getTitle() != null) {
            if (request.getTitle().isBlank()) throw new AppException("Product title cannot be blank", HttpStatus.BAD_REQUEST);
            current.setTitle(request.getTitle().trim());
        }
        if (request.getTechnicalName() != null) current.setTechnicalName(request.getTechnicalName().trim());
        if (request.getDescription() != null) current.setDescription(request.getDescription().trim());
        if (request.getImages() != null) current.setImages(request.getImages());
        if (request.getTechnicalContent() != null) current.setTechnicalContent(request.getTechnicalContent().trim());
        if (request.getFeatures() != null) current.setFeatures(request.getFeatures());
        if (request.getBenefits() != null) current.setBenefits(request.getBenefits());
        if (request.getModeOfAction() != null) current.setModeOfAction(request.getModeOfAction().trim());
        if (request.getSuitableCrops() != null) current.setSuitableCrops(request.getSuitableCrops());
        if (request.getTargetPests() != null) current.setTargetPests(request.getTargetPests());
        if (request.getTargetDiseases() != null) current.setTargetDiseases(request.getTargetDiseases());
        if (request.getDosage() != null) current.setDosage(request.getDosage().trim());
        if (request.getApplicationMethod() != null) current.setApplicationMethod(request.getApplicationMethod().trim());
        if (request.getCategoryId() != null) {
            if (request.getCategoryId().isBlank()) throw new AppException("Category ID cannot be blank", HttpStatus.BAD_REQUEST);
            current.setCategoryId(request.getCategoryId().trim());
        }
        if (request.getDimensions() != null) current.setDimensions(normalizeDimensions(request.getDimensions()));
        if (request.getRefundPolicy() != null) current.setRefundPolicy(request.getRefundPolicy().trim());
        if (request.getProductWeight() != null) current.setProductWeight(request.getProductWeight());
        if (request.getProductWeightUnit() != null) {
            current.setProductWeightUnit(request.getProductWeightUnit().trim().toLowerCase(Locale.ROOT));
        }

        // Variants update (if provided)
        if (request.getVariants() != null) {
            if (request.getVariants().isEmpty()) {
                throw new AppException("At least one product variant is required", HttpStatus.BAD_REQUEST);
            }
            Set<String> existingVariantIds = current.getVariants() == null ? Set.of() : current.getVariants().stream()
                    .map(ProductVariant::getId).filter(java.util.Objects::nonNull).map(v -> v.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            List<ProductVariant> submittedVariants = request.getVariants().stream()
                    .map(SellerProductWriteRequest.VariantWriteRequest::toVariant)
                    .toList();
            List<ProductVariant> sanitized = sanitizeVariants(submittedVariants, current.getVariants(), current.getSku());
            normalizeDefaultVariants(sanitized);
            current.setVariants(new ArrayList<>(sanitized));

            Set<String> retainedVariantIds = sanitized.stream()
                    .map(ProductVariant::getId).filter(java.util.Objects::nonNull).map(v -> v.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            List<String> removedVariantIds = existingVariantIds.stream().filter(v -> !retainedVariantIds.contains(v)).toList();
            for (String variantId : removedVariantIds) cartService.removeVariantFromAllCarts(variantId);
            removeWishlistReferences(removedVariantIds);
        }

        sellerProducts.validateProduct(current);
        current.setStatus("INACTIVE");
        current.setIsAvailable(false);
        current.setIsFeatured(false);
        current.setUpdatedAt(LocalDateTime.now());
        return saveAndInvalidatePublicCatalog(current);
    }

    @Override
    public Product update(String id, AdminProductWriteRequest request) {
        AdminProductUpdateRequest updateRequest = new AdminProductUpdateRequest();
        updateRequest.setSellerId(request.getSellerId());
        updateRequest.setSku(request.getSku());
        updateRequest.setHsnCode(request.getHsnCode());
        updateRequest.setTitle(request.getTitle());
        updateRequest.setTechnicalName(request.getTechnicalName());
        updateRequest.setDescription(request.getDescription());
        updateRequest.setImages(request.getImages());
        updateRequest.setTechnicalContent(request.getTechnicalContent());
        updateRequest.setFeatures(request.getFeatures());
        updateRequest.setBenefits(request.getBenefits());
        updateRequest.setModeOfAction(request.getModeOfAction());
        updateRequest.setSuitableCrops(request.getSuitableCrops());
        updateRequest.setTargetPests(request.getTargetPests());
        updateRequest.setTargetDiseases(request.getTargetDiseases());
        updateRequest.setDosage(request.getDosage());
        updateRequest.setApplicationMethod(request.getApplicationMethod());
        updateRequest.setCategoryId(request.getCategoryId());
        updateRequest.setDimensions(request.getDimensions());
        updateRequest.setRefundPolicy(request.getRefundPolicy());
        updateRequest.setProductWeight(request.getProductWeight());
        updateRequest.setProductWeightUnit(request.getProductWeightUnit());
        updateRequest.setVariants(request.getVariants());
        return update(id, updateRequest);
    }

    @Override
    public Product patch(String id, AdminProductPatchRequest request) {
        AdminProductUpdateRequest updateRequest = new AdminProductUpdateRequest();
        updateRequest.setSku(request.getSku());
        updateRequest.setHsnCode(request.getHsnCode());
        updateRequest.setTitle(request.getTitle());
        updateRequest.setTechnicalName(request.getTechnicalName());
        updateRequest.setDescription(request.getDescription());
        updateRequest.setImages(request.getImages());
        updateRequest.setTechnicalContent(request.getTechnicalContent());
        updateRequest.setFeatures(request.getFeatures());
        updateRequest.setBenefits(request.getBenefits());
        updateRequest.setModeOfAction(request.getModeOfAction());
        updateRequest.setSuitableCrops(request.getSuitableCrops());
        updateRequest.setTargetPests(request.getTargetPests());
        updateRequest.setTargetDiseases(request.getTargetDiseases());
        updateRequest.setDosage(request.getDosage());
        updateRequest.setApplicationMethod(request.getApplicationMethod());
        updateRequest.setCategoryId(request.getCategoryId());
        updateRequest.setDimensions(request.getDimensions());
        updateRequest.setRefundPolicy(request.getRefundPolicy());
        updateRequest.setProductWeight(request.getProductWeight());
        updateRequest.setProductWeightUnit(request.getProductWeightUnit());
        return update(id, updateRequest);
    }

    private ProductDimensions normalizeDimensions(ProductDimensions value) {
        return value == null ? null : new ProductDimensions(value.getLength(), value.getWidth(), value.getHeight(), "cm");
    }

    private List<ProductVariant> sanitizeVariants(List<ProductVariant> submitted, List<ProductVariant> current) {
        return sanitizeVariants(submitted, current, null);
    }

    private List<ProductVariant> sanitizeVariants(List<ProductVariant> submitted, List<ProductVariant> current, String productSku) {
        List<ProductVariant> currentList = current != null ? current : List.of();
        Set<String> retainedIds = new HashSet<>();
        List<ProductVariant> result = new ArrayList<>();
        for (ProductVariant input : submitted) {
            ProductVariant prior = currentList.stream().filter(value -> value.getId() != null && input.getId() != null
                    && value.getId().equalsIgnoreCase(input.getId())).findFirst().orElse(null);
            boolean retain = prior != null && retainedIds.add(prior.getId().toLowerCase(Locale.ROOT));
            ProductVariant clean = new ProductVariant();
            clean.setId(retain ? prior.getId() : new ObjectId().toHexString());
            clean.setSku(input.getSku() != null && !input.getSku().isBlank()
                    ? input.getSku().trim()
                    : (prior != null && prior.getSku() != null ? prior.getSku() : null));
            if (clean.getSku() == null || clean.getSku().isBlank()) {
                String baseSku = productSku != null ? productSku.replaceAll("\\s*-\\s*", "-").trim() : "PROD";
                clean.setSku(String.format("%s-%02d", baseSku, result.size() + 1));
            }
            clean.setLabel(input.getLabel());
            clean.setUnit(input.getUnit() == null ? (input.getPackSizeUnit() == null ? null : input.getPackSizeUnit().trim().toLowerCase(Locale.ROOT)) : input.getUnit().trim().toLowerCase(Locale.ROOT));
            clean.setPackSize(input.getPackSize());
            clean.setPackSizeUnit(input.getPackSizeUnit() == null ? null : input.getPackSizeUnit().trim().toLowerCase(Locale.ROOT));
            clean.setPackQuantity(input.getPackQuantity());
            clean.setPackUnit(input.getPackUnit() == null ? null : input.getPackUnit().trim().toLowerCase(Locale.ROOT));
            clean.setShippedBy(input.getShippedBy() == null ? null : input.getShippedBy().trim());
            clean.setSwg(input.getSwg());
            if (input.getPackSize() != null && input.getPackQuantity() != null) {
                clean.setTotalBaseQuantity(Math.multiplyExact(input.getPackSize().longValue(), input.getPackQuantity().longValue()));
            }
            if (input.getPackSizeUnit() != null) {
                clean.setTotalBaseUnit(input.getPackSizeUnit().trim().toLowerCase(Locale.ROOT));
            }
            clean.setDimensions(input.getDimensions() == null ? null : normalizeDimensions(input.getDimensions()));
            clean.setDisplayRate(input.getDisplayRate());
            clean.setPrintedMrp(input.getPrintedMrp());
            clean.setCostPrice(input.getCostPrice());
            clean.setDiscountRs(input.getDiscountRs());
            clean.setGstPercentage(input.getGstPercentage());
            clean.setDiscountPercentage(input.getDiscountPercentage());
            clean.setStock(input.getStock());
            clean.setDisplayOrder(input.getDisplayOrder());
            clean.setIsDefault(input.getIsDefault());
            result.add(clean);
        }
        return List.copyOf(result);
    }

    @Override public Product addVariant(String productId, AdminProductVariantWriteRequest request) {
        Product product = getEditableProduct(productId);
        if (product.getVariants() == null) product.setVariants(new ArrayList<>());
        ProductVariant added = request.toProductVariant();
        added.setId(new ObjectId().toHexString());
        if (added.getSku() == null || added.getSku().isBlank()) {
            String baseSku = product.getSku() != null ? product.getSku().replaceAll("\\s*-\\s*", "-").trim() : "PROD";
            int vIndex = product.getVariants().size() + 1;
            added.setSku(String.format("%s-%02d", baseSku, vIndex));
        }
        if (Boolean.TRUE.equals(request.getIsDefault())) {
            for (ProductVariant v : product.getVariants()) {
                v.setIsDefault(false);
            }
            added.setIsDefault(true);
        } else if (product.getVariants().isEmpty()) {
            added.setIsDefault(true);
        } else {
            added.setIsDefault(false);
        }
        product.getVariants().add(added);
        return validateAndSaveVariantChange(product);
    }

    @Override public Product updateVariant(String productId, String variantId, AdminProductVariantWriteRequest request) {
        Product product = getEditableProduct(productId);
        ProductVariant existing = findVariant(product, variantId);
        ProductVariant replacement = request.toProductVariant();
        replacement.setId(existing.getId());
        replacement.setDisplayOrder(existing.getDisplayOrder());
        if (replacement.getSku() == null || replacement.getSku().isBlank()) {
            replacement.setSku(existing.getSku());
        }
        Boolean targetDefault = request.getIsDefault() != null ? request.getIsDefault() : existing.getIsDefault();
        if (Boolean.TRUE.equals(targetDefault)) {
            for (ProductVariant v : product.getVariants()) {
                v.setIsDefault(false);
            }
            replacement.setIsDefault(true);
        } else {
            replacement.setIsDefault(false);
        }
        replaceVariant(product, existing, replacement);
        Product saved = validateAndSaveVariantChange(product);
        if (replacement.getStock() != null) {
            webSocketService.broadcast("inventory:stock_update", Map.of(
                    "productId", productId,
                    "variantId", variantId,
                    "stock", replacement.getStock()
            ));
        }
        return saved;
    }

    @Override public Product patchVariant(String productId, String variantId, AdminProductVariantPatchRequest patch) {
        if (!patch.hasChanges()) throw new AppException("At least one variant field must be provided", HttpStatus.BAD_REQUEST);
        Product product = getEditableProduct(productId);
        ProductVariant current = findVariant(product, variantId);
        ProductVariant updated = copyVariant(current);
        if (patch.getSku()!=null) updated.setSku(patch.getSku());
        if (patch.getLabel()!=null) updated.setLabel(patch.getLabel());
        if (patch.getUnit()!=null) updated.setUnit(patch.getUnit());
        if (patch.getPackSize()!=null) updated.setPackSize(patch.getPackSize());
        if (patch.getPackSizeUnit()!=null) updated.setPackSizeUnit(patch.getPackSizeUnit());
        if (patch.getPackQuantity()!=null) updated.setPackQuantity(patch.getPackQuantity());
        if (patch.getPackUnit()!=null) updated.setPackUnit(patch.getPackUnit());
        if (patch.getShippedBy()!=null) updated.setShippedBy(patch.getShippedBy());
        if (patch.getSwg()!=null) updated.setSwg(patch.getSwg());
        if (patch.getDimensions()!=null) updated.setDimensions(patch.getDimensions());
        if (patch.getDisplayRate()!=null) updated.setDisplayRate(patch.getDisplayRate());
        if (patch.getPrintedMrp()!=null) updated.setPrintedMrp(patch.getPrintedMrp());
        if (patch.getCostPrice()!=null) updated.setCostPrice(patch.getCostPrice());
        if (patch.getDiscountRs()!=null) updated.setDiscountRs(patch.getDiscountRs());
        if (patch.getGstPercentage()!=null) updated.setGstPercentage(patch.getGstPercentage());
        if (patch.getDiscountPercentage()!=null) updated.setDiscountPercentage(patch.getDiscountPercentage());
        if (patch.getStock()!=null) updated.setStock(patch.getStock());
        if (patch.getIsDefault() != null) {
            if (Boolean.TRUE.equals(patch.getIsDefault())) {
                for (ProductVariant v : product.getVariants()) {
                    v.setIsDefault(false);
                }
                updated.setIsDefault(true);
            } else {
                updated.setIsDefault(false);
            }
        }
        replaceVariant(product, current, updated);
        Product saved = validateAndSaveVariantChange(product);
        if (patch.getStock() != null) {
            webSocketService.broadcast("inventory:stock_update", Map.of(
                    "productId", productId,
                    "variantId", variantId,
                    "stock", updated.getStock() != null ? updated.getStock() : 0
            ));
        }
        return saved;
    }

    @Override public Product setDefaultVariant(String productId, String variantId, boolean isDefault) {
        Product product = getEditableProduct(productId);
        ProductVariant target = findVariant(product, variantId);
        if (isDefault) {
            for (ProductVariant v : product.getVariants()) {
                boolean match = v.getId() != null && v.getId().equalsIgnoreCase(target.getId());
                v.setIsDefault(match);
            }
        } else {
            target.setIsDefault(false);
        }
        return validateAndSaveVariantChange(product);
    }

    @Override public Product deleteVariant(String productId, String variantId) {
        Product product = getEditableProduct(productId);
        ProductVariant variant = findVariant(product, variantId);
        if (product.getVariants().size() <= 1)
            throw new AppException("A product must retain at least one variant; delete the product instead", HttpStatus.CONFLICT);
        boolean wasDefault = Boolean.TRUE.equals(variant.getIsDefault());
        product.getVariants().remove(variant);
        if (wasDefault) {
            for (int i = 0; i < product.getVariants().size(); i++) {
                product.getVariants().get(i).setIsDefault(i == 0);
            }
        } else {
            normalizeDefaultVariants(product.getVariants());
        }
        Product saved = validateAndSaveVariantChange(product);
        cartService.removeVariantFromAllCarts(variant.getId());
        removeWishlistReferences(List.of(variant.getId()));
        return saved;
    }

    private void normalizeDefaultVariants(List<ProductVariant> variants) {
        if (variants == null || variants.isEmpty()) return;
        boolean foundDefault = false;
        for (ProductVariant v : variants) {
            if (Boolean.TRUE.equals(v.getIsDefault())) {
                if (!foundDefault) {
                    foundDefault = true;
                } else {
                    v.setIsDefault(false);
                }
            }
        }
        if (!foundDefault) {
            variants.get(0).setIsDefault(true);
        }
    }

    private Product getEditableProduct(String id) {
        Product product = findProduct(id);
        if (Boolean.TRUE.equals(product.getIsDeleted()))
            throw new AppException("Deleted products must be restored before editing", HttpStatus.CONFLICT);
        return product;
    }

    private ProductVariant findVariant(Product product, String variantId) {
        if (variantId == null || !ObjectId.isValid(variantId.trim()))
            throw new AppException("Variant ID must be a valid ID", HttpStatus.BAD_REQUEST);
        return product.getVariants() == null ? missingVariant(variantId) : product.getVariants().stream()
                .filter(v -> v.getId() != null && v.getId().equalsIgnoreCase(variantId.trim())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Product variant", "id", variantId));
    }

    private ProductVariant missingVariant(String variantId) {
        throw new ResourceNotFoundException("Product variant", "id", variantId);
    }

    private void replaceVariant(Product product, ProductVariant oldValue, ProductVariant newValue) {
        int index = product.getVariants().indexOf(oldValue);
        product.getVariants().set(index, newValue);
    }

    private Product validateAndSaveVariantChange(Product product) {
        sellerProducts.validateProduct(product);
        // Status and visibility preserved for admin variant operations
        product.setUpdatedAt(LocalDateTime.now());
        return saveAndInvalidatePublicCatalog(product);
    }

    private ProductVariant copyVariant(ProductVariant source) {
        ProductVariant copy = new ProductVariant();
        copy.setId(source.getId()); copy.setSku(source.getSku()); copy.setDisplayOrder(source.getDisplayOrder()); copy.setIsDefault(source.getIsDefault());
        copy.setLabel(source.getLabel()); copy.setUnit(source.getUnit()); copy.setPackSize(source.getPackSize());
        copy.setPackSizeUnit(source.getPackSizeUnit()); copy.setPackQuantity(source.getPackQuantity()); copy.setPackUnit(source.getPackUnit());
        copy.setShippedBy(source.getShippedBy()); copy.setSwg(source.getSwg()); copy.setDimensions(source.getDimensions());
        copy.setDisplayRate(source.getDisplayRate()); copy.setPrintedMrp(source.getPrintedMrp()); copy.setCostPrice(source.getCostPrice());
        copy.setDiscountRs(source.getDiscountRs()); copy.setGstPercentage(source.getGstPercentage());
        copy.setDiscountPercentage(source.getDiscountPercentage()); copy.setStock(source.getStock());
        return copy;
    }

    @Override public Product softDelete(String id) {
        Product p=findProduct(id);
        if (Boolean.TRUE.equals(p.getIsDeleted())) { invalidatePublicCatalog(); return p; }
        p.setStatus("INACTIVE"); p.setIsAvailable(false); p.setIsFeatured(false); p.setIsDeleted(true);
        p.setDeletedAt(LocalDateTime.now()); p.setUpdatedAt(LocalDateTime.now());
        Product saved = saveAndInvalidatePublicCatalog(p);
        webSocketService.broadcast("products:delete", Map.of(
                "productId", id,
                "isPermanent", false
        ));
        return saved;
    }

    @Override public Product restore(String id) {
        Product p=findProduct(id);
        if (!Boolean.TRUE.equals(p.getIsDeleted())) { invalidatePublicCatalog(); return p; }
        p.setIsDeleted(false); p.setDeletedAt(null); p.setStatus("INACTIVE"); p.setIsAvailable(false); p.setIsFeatured(false);
        p.setUpdatedAt(LocalDateTime.now()); return saveAndInvalidatePublicCatalog(p);
    }

    @Override public void permanentlyDelete(String id) {
        permanentlyDeleteMany(List.of(id));
    }

    @Override
    public List<String> permanentlyDeleteMany(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<String> deletedIds = new ArrayList<>();
        List<String> allVariantIds = new ArrayList<>();

        for (String id : ids) {
            Product product = products.findById(id).orElse(null);
            if (product == null) {
                product = products.findByProductCode(id).orElse(null);
            }
            if (product == null) {
                product = products.findBySku(id).orElse(null);
            }
            if (product != null) {
                String actualId = product.getId();
                if (product.getProductImages() != null && product.getSellerId() != null) {
                    for (ProductImage image : product.getProductImages()) {
                        try {
                            imageStorage.deleteImageVariants(product.getSellerId(), actualId, image);
                        } catch (Exception ignored) {}
                    }
                }
                if (product.getVariants() != null) {
                    product.getVariants().stream()
                            .map(ProductVariant::getId)
                            .filter(java.util.Objects::nonNull)
                            .forEach(allVariantIds::add);
                }
                try {
                    cartService.removeProductFromAllCarts(actualId);
                } catch (Exception ignored) {}

                products.delete(product);
                deletedIds.add(actualId);
                webSocketService.broadcast("products:delete", Map.of(
                        "productId", actualId,
                        "isPermanent", true
                ));
            }
        }

        if (!deletedIds.isEmpty()) {
            removeWishlistReferences(allVariantIds);
            invalidatePublicCatalog();
            webSocketService.broadcast("products:refresh", Map.of(
                    "message", "Catalog invalidated",
                    "deletedCount", deletedIds.size(),
                    "timestamp", java.time.Instant.now().toString()
            ));
        }

        return deletedIds;
    }

    private void removeWishlistReferences(List<String> variantIds) {
        if (variantIds.isEmpty()) return;
        String lastId = null;
        while (true) {
            Query pageQuery = Query.query(Criteria.where("variantIds").in(variantIds))
                    .with(Sort.by(Sort.Direction.ASC, "_id")).limit(500);
            if (lastId != null) pageQuery.addCriteria(Criteria.where("_id").gt(lastId));
            List<com.ebs.biocrop.entity.Wishlist> batch = mongoTemplate.find(pageQuery, com.ebs.biocrop.entity.Wishlist.class);
            if (batch.isEmpty()) break;
            for (com.ebs.biocrop.entity.Wishlist wishlist : batch) {
                org.springframework.data.mongodb.core.query.Update update = new org.springframework.data.mongodb.core.query.Update()
                        .pullAll("variantIds", variantIds.toArray()).set("updatedAt", LocalDateTime.now());
                for (String variantId : variantIds) update.unset("variantProductIds." + variantId);
                mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(wishlist.getId())), update, com.ebs.biocrop.entity.Wishlist.class);
                lastId = wishlist.getId();
            }
        }
    }

    @Override
    public Product review(String id, boolean approved) {
        Product product = findProduct(id);
        ensureNotDeleted(product);
        if (approved) {
            if (product.getProductImages() == null || product.getProductImages().isEmpty()) {
                throw new AppException(
                        "At least one product image is required before approval",
                        HttpStatus.CONFLICT);
            }
            if (product.getSellerId() == null || product.getSellerId().isBlank()) {
                throw new AppException("Only seller-owned listings can be approved", HttpStatus.CONFLICT);
            }
            Category category = product.getCategoryId() == null ? null : categories.findById(product.getCategoryId())
                    .filter(item -> !Boolean.FALSE.equals(item.getIsActive()))
                    .filter(item -> item.getLevel() != null && item.getLevel() >= 0 && item.getLevel() <= 2)
                    .orElse(null);
            if (category == null) {
                throw new AppException("Product category is missing, inactive, or outside the supported depth", HttpStatus.CONFLICT);
            }
            boolean hasChildren = categories.hasActiveChildren(category.getId());
            if (hasChildren) {
                throw new AppException("Products must use a subcategory or a category without children", HttpStatus.CONFLICT);
            }
            validatePublishReady(product);
        }
        product.setStatus(approved ? "ACTIVE" : "INACTIVE");
        product.setIsAvailable(approved);
        if (!approved) product.setIsFeatured(false);
        product.setUpdatedAt(LocalDateTime.now());
        Product saved = saveAndInvalidatePublicCatalog(product);
        if (saved.getSellerId() != null && !saved.getSellerId().isBlank()) {
            webSocketService.sendToUser(saved.getSellerId(), "products:review_status", Map.of(
                    "productId", saved.getId(),
                    "title", saved.getTitle() != null ? saved.getTitle() : "",
                    "status", approved ? "APPROVED" : "REJECTED"
            ));
        }
        return saved;
    }

    private void validatePublishReady(Product product) {
        if (product.getVariants() == null || product.getVariants().isEmpty()) {
            throw new AppException("At least one complete sellable variant is required before approval", HttpStatus.CONFLICT);
        }
        for (ProductVariant variant : product.getVariants()) {
            if (variant == null || variant.getLabel() == null || variant.getLabel().isBlank()
                    || "default title".equalsIgnoreCase(variant.getLabel().trim())) {
                throw new AppException("Every variant needs a specific package label before approval", HttpStatus.CONFLICT);
            }
            if (variant.getPackSize() == null || variant.getPackSize() <= 0
                    || variant.getPackSizeUnit() == null || variant.getPackSizeUnit().isBlank()
                    || variant.getPackQuantity() == null || variant.getPackQuantity() <= 0) {
                throw new AppException("Every variant needs a complete package size and quantity before approval", HttpStatus.CONFLICT);
            }
            if (variant.getDisplayRate() == null || !Double.isFinite(variant.getDisplayRate()) || variant.getDisplayRate() <= 0) {
                throw new AppException("Every variant needs a positive selling price before approval", HttpStatus.CONFLICT);
            }
            if (variant.getStock() == null || variant.getStock() < 0) {
                throw new AppException("Every variant needs a confirmed stock quantity before approval", HttpStatus.CONFLICT);
            }
            ProductDimensions dimensions = variant.getDimensions() == null ? product.getDimensions() : variant.getDimensions();
            if (dimensions == null || !"cm".equalsIgnoreCase(dimensions.getUnit())
                    || !positive(dimensions.getLength()) || !positive(dimensions.getWidth()) || !positive(dimensions.getHeight())) {
                throw new AppException("Every variant needs positive package dimensions in centimeters before approval", HttpStatus.CONFLICT);
            }
        }
    }

    private boolean positive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }

    @Override
    public Product setAvailability(String id, boolean available) {
        Product product = findProduct(id);
        ensureNotDeleted(product);
        if (available && !"ACTIVE".equalsIgnoreCase(product.getStatus())) {
            throw new AppException("Only an approved product can be made available", HttpStatus.CONFLICT);
        }
        product.setIsAvailable(available);
        product.setUpdatedAt(LocalDateTime.now());
        return saveAndInvalidatePublicCatalog(product);
    }

    @Override
    public Product setFeatured(String id, boolean featured) {
        Product product = findProduct(id);
        ensureNotDeleted(product);
        if (featured && (!"ACTIVE".equalsIgnoreCase(product.getStatus())
                || !Boolean.TRUE.equals(product.getIsAvailable()))) {
            throw new AppException("Only an active, available product can be featured", HttpStatus.CONFLICT);
        }
        product.setIsFeatured(featured);
        product.setUpdatedAt(LocalDateTime.now());
        return saveAndInvalidatePublicCatalog(product);
    }

    @Override
    public Product assignCollections(String productId, ProductCollectionAssignmentRequest request) {
        Product product = findProduct(productId);
        ensureNotDeleted(product);
        Set<String> collectionIds = normalizeObjectIds(request.collectionIds(), "collection");
        Set<String> subCollectionIds = normalizeObjectIds(request.subCollectionIds(), "subcollection");
        List<String> assigned = new ArrayList<>();
        List<String> assignedSubcollections = new ArrayList<>();
        for (String collectionId : collectionIds) {
            ProductCollection collection = collections.findById(collectionId)
                    .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                    .orElseThrow(() -> new AppException(
                            "Every selected collection must exist and be active", HttpStatus.BAD_REQUEST));
            assigned.add(collectionId);
            if (collection.getSubCollections() == null) continue;
            for (String subCollectionId : subCollectionIds) {
                ProductCollection.SubCollection sub = collection.getSubCollections().stream()
                        .filter(value -> value.getId() != null
                                && subCollectionId.equals(value.getId().toHexString())
                                && Boolean.TRUE.equals(value.getIsActive()))
                        .findFirst().orElse(null);
                if (sub != null) assignedSubcollections.add(subCollectionId);
            }
        }
        if (assignedSubcollections.size() != subCollectionIds.size()) {
            throw new AppException(
                    "Every selected subcollection must belong to one of the selected active collections",
                    HttpStatus.BAD_REQUEST);
        }
        assigned.addAll(assignedSubcollections);
        product.setAssignedCollections(assigned.stream().distinct().toList());
        product.setUpdatedAt(LocalDateTime.now());
        return saveAndInvalidatePublicCatalog(product);
    }

    private Set<String> normalizeObjectIds(List<String> ids, String label) {
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String id : ids) {
            if (!ObjectId.isValid(id)) {
                throw new AppException("Each " + label + " ID must be a valid MongoDB ObjectId", HttpStatus.BAD_REQUEST);
            }
            if (!normalized.add(new ObjectId(id).toHexString())) {
                throw new AppException("Duplicate " + label + " IDs are not allowed", HttpStatus.BAD_REQUEST);
            }
        }
        return normalized;
    }

    @Override
    public Product addImages(String productId, List<MultipartFile> files) {
        if (files == null || files.isEmpty() || files.size() > MAX_PRODUCT_IMAGES) {
            throw new AppException(
                    "Upload between 1 and 6 product images per request",
                    HttpStatus.BAD_REQUEST);
        }

        Product product = findProduct(productId);
        ensureNotDeleted(product);

        int currentImageCount = product.getProductImages() == null
                ? 0
                : product.getProductImages().size();
        int remainingSlots = Math.max(0, MAX_PRODUCT_IMAGES - currentImageCount);
        if (files.size() > remainingSlots && remainingSlots > 0) {
            throw new AppException(
                    "This product has only " + remainingSlots + " image slot(s) remaining",
                    HttpStatus.CONFLICT);
        } else if (remainingSlots <= 0) {
            throw new AppException(
                    "This product already has the maximum number of images (" + MAX_PRODUCT_IMAGES + ")",
                    HttpStatus.CONFLICT);
        }


        if (product.getSellerId() == null || product.getSellerId().isBlank()) {
            throw new AppException(
                    "Product must have a seller before images can be uploaded",
                    HttpStatus.CONFLICT);
        }

        if (files.stream().anyMatch(file -> file == null || file.isEmpty())) {
            throw new AppException("Uploaded image files must not be empty", HttpStatus.BAD_REQUEST);
        }

        // Validate the whole batch before writing any object to GCS.
        try {
            for (MultipartFile file : files) {
                imageVariants.validateImage(file.getBytes());
            }
        } catch (IOException readFailure) {
            throw new AppException(
                    "Could not read one of the uploaded image files",
                    HttpStatus.BAD_REQUEST);
        }

        List<ProductImage> uploadedImages = new ArrayList<>();

        try {
            for (MultipartFile file : files) {
                ProductImageVariantService.Variants variants =
                        imageVariants.createVariants(file.getBytes());
                ProductImage image = imageStorage.uploadImage(
                        product.getSellerId(),
                        product.getId(),
                        variants,
                        currentImageCount + uploadedImages.size());
                uploadedImages.add(image);
            }

            if (product.getProductImages() == null) {
                product.setProductImages(new ArrayList<>());
            }
            product.getProductImages().addAll(uploadedImages);
            product.setUpdatedAt(LocalDateTime.now());
            return saveAndInvalidatePublicCatalog(product);
        } catch (IOException readFailure) {
            AppException uploadFailure = new AppException(
                    "Could not read one of the uploaded image files",
                    HttpStatus.BAD_REQUEST);
            cleanupUploadedImages(product, uploadedImages, uploadFailure);
            throw uploadFailure;
        } catch (RuntimeException saveFailure) {
            cleanupUploadedImages(product, uploadedImages, saveFailure);
            throw saveFailure;
        }
    }

    private void cleanupUploadedImages(
            Product product,
            List<ProductImage> uploadedImages,
            RuntimeException failure) {
        for (ProductImage image : uploadedImages) {
            try {
                imageStorage.deleteImageVariants(
                        product.getSellerId(),
                        product.getId(),
                        image);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    @Override
    public Product deleteImages(String productId, List<String> imageIds) {
        Product product = findProduct(productId);
        ensureNotDeleted(product);

        if (product.getProductImages() == null || product.getProductImages().isEmpty()) {
            throw new ResourceNotFoundException("Product image", "id", imageIds);
        }

        if (imageIds == null || imageIds.isEmpty()) {
            throw new AppException("At least one image ID is required", HttpStatus.BAD_REQUEST);
        }
        if (imageIds.size() > MAX_PRODUCT_IMAGES) {
            throw new AppException("At most 6 image IDs can be deleted at once", HttpStatus.BAD_REQUEST);
        }

        Set<String> requestedIds = new HashSet<>(imageIds);
        if (requestedIds.size() != imageIds.size()) {
            throw new AppException("Duplicate image IDs are not allowed", HttpStatus.BAD_REQUEST);
        }

        Map<String, ProductImage> imagesById = product.getProductImages().stream()
                .collect(Collectors.toMap(ProductImage::getId, Function.identity()));
        List<ProductImage> imagesToDelete = new ArrayList<>();
        for (String requestedId : imageIds) {
            ProductImage image = imagesById.get(requestedId);
            if (image == null) {
                throw new ResourceNotFoundException("Product image", "id", requestedId);
            }
            imagesToDelete.add(image);
        }

        product.getProductImages().removeAll(imagesToDelete);
        if (product.getProductImages().isEmpty()) {
            product.setStatus("INACTIVE");
            product.setIsAvailable(false);
            product.setIsFeatured(false);
        }
        for (int index = 0; index < product.getProductImages().size(); index++) {
            product.getProductImages().get(index).setDisplayOrder(index);
        }
        product.setUpdatedAt(LocalDateTime.now());

        Product saved = saveAndInvalidatePublicCatalog(product);

        // Delete from GCS only after MongoDB no longer references these images.
        for (ProductImage image : imagesToDelete) {
            imageStorage.deleteImageVariants(
                    product.getSellerId(),
                    product.getId(),
                    image);
        }

        return saved;
    }

    private Product saveAndInvalidatePublicCatalog(Product product) {
        Product saved;
        try { saved = products.save(product); }
        catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new AppException("SKU is already in use", HttpStatus.CONFLICT);
        }
        invalidatePublicCatalog();
        webSocketService.broadcast("products:update", saved);
        return saved;
    }

    private void invalidatePublicCatalog() {
        redisCache.invalidateRegion("public-products");
        redisCache.invalidateRegion("public-collections");
    }

    private Product findProduct(String id) {
        return products.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));
    }

    private void ensureNotDeleted(Product product) {
        if (Boolean.TRUE.equals(product.getIsDeleted())) throw new AppException("Deleted products must be restored before modification", HttpStatus.CONFLICT);
    }
}
