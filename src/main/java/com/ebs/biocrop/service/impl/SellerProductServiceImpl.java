package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.websocket.WebSocketService;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.dto.request.SellerProductWriteRequest;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductDimensions;
import com.ebs.biocrop.entity.ProductImage;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.service.SellerProductService;
import com.ebs.biocrop.service.RedisJsonCache;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class SellerProductServiceImpl implements SellerProductService {
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final MongoTemplate mongoTemplate;
    private final RedisJsonCache redisCache;
    private final WebSocketService webSocketService;

    public SellerProductServiceImpl(ProductRepository products, CategoryRepository categories,
                                    MongoTemplate mongoTemplate,
                                    RedisJsonCache redisCache,
                                    WebSocketService webSocketService) {
        this.products = products;
        this.categories = categories;
        this.mongoTemplate = mongoTemplate;
        this.redisCache = redisCache;
        this.webSocketService = webSocketService;
    }

    @Override
    public Product create(String sellerId, SellerProductWriteRequest writeRequest) {
        Product request = writeRequest.toProduct();
        validate(request);
        if (products.existsBySkuIgnoreCase(request.getSku().trim())) {
            throw new AppException("SKU is already in use", HttpStatus.CONFLICT);
        }
        Product listing = new Product();
        copySellerFields(request, listing, null);
        listing.setSellerId(sellerId);
        listing.setSellerName(findSellerShopName(sellerId));
        listing.setProductCode(nextProductCode(sellerId));
        listing.setStatus("ACTIVE");
        listing.setIsAvailable(true);
        listing.setIsFeatured(true);
        listing.setAverageRating(null);
        listing.setCreatedAt(LocalDateTime.now());
        listing.setUpdatedAt(listing.getCreatedAt());
        return save(listing);
    }

    @Override
    public Page<Product> list(String sellerId, int page, int size) {
        return products.findActiveBySellerId(sellerId,
                PageRequestSupport.create(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
    }

    @Override
    public Product get(String sellerId, String id) {
        Product product = products.findByIdAndSellerId(id, sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seller product", "id", id));
        if (Boolean.TRUE.equals(product.getIsDeleted())) {
            throw new ResourceNotFoundException("Seller product", "id", id);
        }
        return product;
    }

    @Override
    public Product update(String sellerId, String id, SellerProductWriteRequest writeRequest) {
        Product request = writeRequest.toProduct();
        Product current = products.findByIdAndSellerId(id, sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seller product", "id", id));
        if (Boolean.TRUE.equals(current.getIsDeleted())) throw new ResourceNotFoundException("Seller product", "id", id);
        validate(request);
        String requestedSku = request.getSku().trim();
        if (products.existsBySkuIgnoreCase(requestedSku) && !requestedSku.equalsIgnoreCase(current.getSku())) {
            throw new AppException("SKU is already in use", HttpStatus.CONFLICT);
        }
        Product listing = new Product();
        copySellerFields(request, listing, current);
        listing.setId(current.getId());
        listing.setProductCode(current.getProductCode());
        listing.setSellerId(current.getSellerId());
        listing.setSellerName(findSellerShopName(current.getSellerId()));
        listing.setStatus("INACTIVE");
        listing.setIsAvailable(false);
        listing.setIsFeatured(false);
        listing.setAverageRating(current.getAverageRating());
        listing.setCreatedAt(current.getCreatedAt());
        listing.setUpdatedAt(LocalDateTime.now());
        listing.setVersion(current.getVersion());
        return save(listing);
    }

    private Product save(Product product) {
        try {
            Product saved = products.save(product);
            redisCache.invalidateRegion("public-products");
            redisCache.invalidateRegion("public-collections");
            webSocketService.broadcast("products:update", saved);
            return saved;
        } catch (DuplicateKeyException exception) {
            throw new AppException("SKU is already in use", HttpStatus.CONFLICT);
        }
    }

    private String nextProductCode(String sellerId) {
        if (!ObjectId.isValid(sellerId)) {
            throw new AppException("Seller ID must be a valid MongoDB ObjectId", HttpStatus.CONFLICT);
        }
        Query sellerQuery = Query.query(Criteria.where("_id").is(new ObjectId(sellerId)));
        UserSequence sequence = mongoTemplate.findAndModify(
                sellerQuery,
                new Update().inc("productCodeSequence", 1),
                FindAndModifyOptions.options().returnNew(true),
                UserSequence.class,
                "users");
        if (sequence == null || sequence.getProductCodeSequence() == null) {
            throw new ResourceNotFoundException("Seller", "id", sellerId);
        }
        return "PRD-" + sellerId.toUpperCase(Locale.ROOT) + "-"
                + String.format(Locale.ROOT, "%06d", sequence.getProductCodeSequence());
    }

    private void validate(Product product) {
        if (product.getTitle() == null || product.getTitle().isBlank()) throw badRequest("Product title is required");
        if (product.getSku() == null || product.getSku().isBlank()) throw badRequest("SKU is required");
        if (product.getProductWeight() != null && !finiteNonnegative(product.getProductWeight())) throw badRequest("Product weight must be a finite nonnegative number");
        if (product.getProductWeightUnit() != null
                && !Set.of("g", "kg").contains(product.getProductWeightUnit().trim().toLowerCase(Locale.ROOT))) {
            throw badRequest("Product weight unit must be g or kg");
        }
        if (product.getCategoryId() == null || product.getCategoryId().isBlank()) throw badRequest("Category ID is required");
        Category category = categories.findById(product.getCategoryId())
                .filter(item -> !Boolean.FALSE.equals(item.getIsActive()))
                .filter(item -> item.getLevel() != null && item.getLevel() <= 2)
                .orElseThrow(() -> badRequest("Selected category does not exist or is inactive"));
        if (categories.hasActiveChildren(category.getId())) throw badRequest("Products must use a leaf category");
        if (product.getVariants() == null || product.getVariants().isEmpty()) throw badRequest("At least one product variant is required");
        for (ProductVariant variant : product.getVariants()) validateVariant(product, variant);
    }

    @Override public void validateProduct(Product product) { validate(product); }

    private void validateVariant(Product product, ProductVariant variant) {
        ProductDimensions dimensions = variant.getDimensions() == null ? product.getDimensions() : variant.getDimensions();
        if (dimensions == null) {
            dimensions = new ProductDimensions(20.0, 10.0, 10.0, "cm");
            variant.setDimensions(dimensions);
            if (product.getDimensions() == null) product.setDimensions(dimensions);
        } else {
            if (dimensions.getUnit() == null || dimensions.getUnit().isBlank()) dimensions.setUnit("cm");
            if (!positive(dimensions.getLength())) dimensions.setLength(20.0);
            if (!positive(dimensions.getWidth())) dimensions.setWidth(10.0);
            if (!positive(dimensions.getHeight())) dimensions.setHeight(10.0);
        }
        if (!"cm".equalsIgnoreCase(dimensions.getUnit())) throw badRequest("Variant dimensions must use centimeters (cm)");
        if (!positive(dimensions.getLength()) || !positive(dimensions.getWidth()) || !positive(dimensions.getHeight())) {
            throw badRequest("Variant length, width, and height must be positive");
        }
        if (variant.getPackSize() == null || variant.getPackSize() <= 0) {
            variant.setPackSize(1);
        }
        if (variant.getPackSizeUnit() == null || variant.getPackSizeUnit().isBlank()) {
            variant.setPackSizeUnit(variant.getUnit() != null && !variant.getUnit().isBlank() ? variant.getUnit() : "unit");
        }
        if (variant.getPackQuantity() == null || variant.getPackQuantity() <= 0) {
            variant.setPackQuantity(1);
        }
        if (variant.getUnit() == null || variant.getUnit().isBlank()) {
            variant.setUnit("unit");
        }
        if (variant.getSwg() != null && !finiteNonnegative(variant.getSwg())) variant.setSwg(null);
        if (!finiteNonnegative(variant.getDisplayRate())) variant.setDisplayRate(0.0);
        if (variant.getStock() == null || variant.getStock() < 0) variant.setStock(0);
        if (variant.getPrintedMrp() == null || !finiteNonnegative(variant.getPrintedMrp()) || variant.getPrintedMrp() < variant.getDisplayRate()) {
            variant.setPrintedMrp(variant.getDisplayRate());
        }
        if (variant.getGstPercentage() != null && (!Double.isFinite(variant.getGstPercentage())
                || variant.getGstPercentage() < 0 || variant.getGstPercentage() > 100)) throw badRequest("GST percentage must be between 0 and 100");
        if (variant.getCostPrice() != null && !finiteNonnegative(variant.getCostPrice())) throw badRequest("Cost price cannot be negative");
        if (variant.getDiscountRs() != null && !finiteNonnegative(variant.getDiscountRs())) throw badRequest("Discount amount cannot be negative");
        if (variant.getDiscountPercentage() != null && (!Double.isFinite(variant.getDiscountPercentage())
                || variant.getDiscountPercentage() < 0 || variant.getDiscountPercentage() > 100)) throw badRequest("Discount percentage must be between 0 and 100");
        try {
            Math.multiplyExact(variant.getPackSize().longValue(), variant.getPackQuantity().longValue());
        } catch (ArithmeticException exception) {
            throw badRequest("Combined package quantity is too large");
        }
    }

    private void copySellerFields(Product source, Product target, Product existing) {
        target.setSku(source.getSku().trim());
        target.setHsnCode(source.getHsnCode());
        target.setTitle(source.getTitle().trim()); target.setTechnicalName(source.getTechnicalName());
        target.setDescription(source.getDescription());
        target.setImages(source.getImages()); target.setTechnicalContent(source.getTechnicalContent());
        target.setProductImages(
                existing == null ? List.of() : existing.getProductImages());
        target.setFeatures(sanitizeStringList(source.getFeatures())); target.setBenefits(sanitizeStringList(source.getBenefits()));
        target.setModeOfAction(source.getModeOfAction()); target.setSuitableCrops(sanitizeStringList(source.getSuitableCrops()));
        target.setTargetPests(sanitizeStringList(source.getTargetPests())); target.setTargetDiseases(sanitizeStringList(source.getTargetDiseases()));
        target.setDosage(source.getDosage()); target.setApplicationMethod(source.getApplicationMethod());
        target.setCategoryId(source.getCategoryId());
        target.setAssignedCollections(existing == null ? List.of()
                : normalizeAssignedCollections(existing.getAssignedCollections()));
        target.setDimensions(normalizeDimensions(source.getDimensions()));
        target.setRefundPolicy(source.getRefundPolicy()); target.setProductWeight(source.getProductWeight());
        target.setProductWeightUnit(source.getProductWeightUnit() == null ? null : source.getProductWeightUnit().trim().toLowerCase(Locale.ROOT));
        List<ProductVariant> oldVariants = existing == null || existing.getVariants() == null ? List.of() : existing.getVariants();
        target.setVariants(sanitizeVariants(source.getVariants(), oldVariants, source.getSku()));
    }

    private String findSellerShopName(String sellerId) {
        User seller = mongoTemplate.findById(sellerId, User.class);
        if (seller == null || seller.getRole() != UserRole.ROLE_SELLER
                || Boolean.TRUE.equals(seller.getIsBlocked()) || Boolean.TRUE.equals(seller.getIsDeleted())) {
            throw new ResourceNotFoundException("Seller", "id", sellerId);
        }
        return seller.getShopName();
    }

    private List<String> sanitizeStringList(List<String> list) {
        if (list == null) return List.of();
        return list.stream().filter(java.util.Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private List<String> normalizeAssignedCollections(List<String> assignedCollections) {
        if (assignedCollections == null) return List.of();
        return assignedCollections.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().toList();
    }

    private List<ProductVariant> sanitizeVariants(List<ProductVariant> submitted, List<ProductVariant> current) {
        return sanitizeVariants(submitted, current, null);
    }

    private List<ProductVariant> sanitizeVariants(List<ProductVariant> submitted, List<ProductVariant> current, String productSku) {
        List<ProductVariant> currentList = current != null ? current : List.of();
        Set<String> retainedIds = new HashSet<>();
        List<ProductVariant> result = new ArrayList<>();
        for (ProductVariant input : submitted) {
            ProductVariant prior = null;
            if (input.getId() != null && !input.getId().isBlank()) {
                prior = currentList.stream()
                        .filter(v -> v.getId() != null && v.getId().equalsIgnoreCase(input.getId().trim()))
                        .findFirst().orElse(null);
            }
            if (prior == null && input.getSku() != null && !input.getSku().isBlank()) {
                prior = currentList.stream()
                        .filter(v -> v.getSku() != null && v.getSku().equalsIgnoreCase(input.getSku().trim())
                                && (v.getId() == null || !retainedIds.contains(v.getId().toLowerCase(Locale.ROOT))))
                        .findFirst().orElse(null);
            }

            boolean retain = prior != null && retainedIds.add(prior.getId().toLowerCase(Locale.ROOT));
            ProductVariant clean = new ProductVariant();
            clean.setId(retain ? prior.getId() : (input.getId() != null && ObjectId.isValid(input.getId()) ? input.getId() : new ObjectId().toHexString()));
            clean.setSku(input.getSku() != null && !input.getSku().isBlank()
                    ? input.getSku().trim()
                    : (prior != null && prior.getSku() != null ? prior.getSku() : null));
            if (clean.getSku() == null || clean.getSku().isBlank()) {
                String baseSku = productSku != null ? productSku.replaceAll("\\s*-\\s*", "-").trim() : "PROD";
                clean.setSku(String.format("%s-%02d", baseSku, result.size() + 1));
            }
            clean.setLabel(input.getLabel() != null && !input.getLabel().isBlank() ? input.getLabel() : "Variant " + (result.size() + 1));
            String unit = input.getUnit() == null ? (input.getPackSizeUnit() == null ? "ml" : input.getPackSizeUnit().trim().toLowerCase(Locale.ROOT)) : input.getUnit().trim().toLowerCase(Locale.ROOT);
            clean.setUnit(unit.isBlank() ? "ml" : unit);
            clean.setPackSize(input.getPackSize() != null && input.getPackSize() > 0 ? input.getPackSize() : 1);
            clean.setPackSizeUnit(input.getPackSizeUnit() != null && !input.getPackSizeUnit().isBlank() ? input.getPackSizeUnit().trim().toLowerCase(Locale.ROOT) : clean.getUnit());
            clean.setPackQuantity(input.getPackQuantity() != null && input.getPackQuantity() > 0 ? input.getPackQuantity() : 1);
            clean.setPackUnit(input.getPackUnit() == null || input.getPackUnit().isBlank() ? "bottle" : input.getPackUnit().trim().toLowerCase(Locale.ROOT));
            clean.setShippedBy(input.getShippedBy() == null || input.getShippedBy().isBlank() ? "essentialbiocropscience" : input.getShippedBy().trim());
            clean.setSwg(input.getSwg());
            clean.setTotalBaseQuantity(Math.multiplyExact(clean.getPackSize().longValue(), clean.getPackQuantity().longValue()));
            clean.setTotalBaseUnit(clean.getPackSizeUnit());
            clean.setDimensions(input.getDimensions() == null ? new ProductDimensions(20.0, 10.0, 10.0, "cm") : normalizeDimensions(input.getDimensions()));

            double dispRate = input.getDisplayRate() != null && input.getDisplayRate() >= 0 ? input.getDisplayRate() : 0.0;
            clean.setDisplayRate(dispRate);
            double printedMrp = (input.getPrintedMrp() != null && input.getPrintedMrp() >= dispRate) ? input.getPrintedMrp() : dispRate;
            clean.setPrintedMrp(printedMrp);
            clean.setCostPrice(input.getCostPrice() != null && input.getCostPrice() >= 0 ? input.getCostPrice() : 0.0);
            clean.setDiscountRs(printedMrp > dispRate ? (printedMrp - dispRate) : 0.0);
            clean.setGstPercentage(input.getGstPercentage() != null && input.getGstPercentage() >= 0 ? input.getGstPercentage() : 5.0);
            clean.setDiscountPercentage(printedMrp > 0 ? ((printedMrp - dispRate) / printedMrp * 100) : 0.0);
            clean.setStock(input.getStock() != null && input.getStock() >= 0 ? input.getStock() : 0);
            clean.setDisplayOrder(input.getDisplayOrder() != null ? input.getDisplayOrder() : (result.size() + 1));
            clean.setIsDefault(input.getIsDefault());
            result.add(clean);
        }
        return List.copyOf(result);
    }

    private AppException badRequest(String message) { return new AppException(message, HttpStatus.BAD_REQUEST); }
    private boolean positive(Double value) { return value != null && Double.isFinite(value) && value > 0; }
    private boolean finiteNonnegative(Double value) { return value != null && Double.isFinite(value) && value >= 0; }
    private ProductDimensions normalizeDimensions(ProductDimensions value) {
        return value == null ? null : new ProductDimensions(value.getLength(), value.getWidth(), value.getHeight(), "cm");
    }

    private static class UserSequence {
        private Long productCodeSequence;
        public Long getProductCodeSequence() { return productCodeSequence; }
        public void setProductCodeSequence(Long productCodeSequence) { this.productCodeSequence = productCodeSequence; }
    }
}
