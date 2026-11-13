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

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final SellerCodeService sellerCodeService;

    public SellerProductServiceImpl(ProductRepository products, CategoryRepository categories,
                                    MongoTemplate mongoTemplate,
                                    RedisJsonCache redisCache,
                                    WebSocketService webSocketService,
                                    SellerCodeService sellerCodeService) {
        this.products = products;
        this.categories = categories;
        this.mongoTemplate = mongoTemplate;
        this.redisCache = redisCache;
        this.webSocketService = webSocketService;
        this.sellerCodeService = sellerCodeService;
    }

    @Override
    public Product create(String sellerId, SellerProductWriteRequest writeRequest) {
        Product request = writeRequest.toProduct();
        validate(request);
        Product listing = new Product();
        String safeSellerId = (sellerId != null && !sellerId.isBlank()) ? sellerId.trim() : null;
        listing.setSellerId(safeSellerId);
        if (safeSellerId != null) {
            listing.setProductCode(nextProductCode(safeSellerId));
            listing.setSku(sellerCodeService.productSku(safeSellerId, listing.getProductCode()));
            listing.setSellerName(findSellerShopName(safeSellerId));
        } else {
            // Derive platform product identity from the Mongo ObjectId; this avoids
            // introducing a separate sequence collection for seller-less listings.
            String productId = new ObjectId().toHexString();
            listing.setId(productId);
            listing.setProductCode("PRD-EBS-" + productId.toUpperCase(Locale.ROOT));
            listing.setSku(listing.getProductCode());
            listing.setSellerName(null);
        }
        copySellerFields(request, listing, null);
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
        if (request.getSku() != null && !request.getSku().isBlank()
                && !request.getSku().trim().equalsIgnoreCase(current.getSku())) {
            throw new AppException("Product SKU is system-managed and cannot be changed", HttpStatus.CONFLICT);
        }
        Product listing = new Product();
        copySellerFields(request, listing, current);
        listing.setId(current.getId());
        listing.setSku(current.getSku());
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
        List<ProductDimensions> dimensions = variant.getDimensions();
        if (dimensions == null || dimensions.isEmpty()) {
            dimensions = List.of(new ProductDimensions(20.0, 10.0, 10.0, "cm"));
            variant.setDimensions(dimensions);
        } else {
            dimensions = normalizeDimensions(dimensions);
            variant.setDimensions(dimensions);
        }
        for (ProductDimensions dimension : dimensions) {
            if (!"cm".equalsIgnoreCase(dimension.getUnit())) throw badRequest("Variant dimensions must use centimeters (cm)");
            if (!positive(dimension.getLength()) || !positive(dimension.getWidth()) || !positive(dimension.getHeight())) {
                throw badRequest("Variant length, width, and height must be positive");
            }
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
        if (variant.getStock() == null || variant.getStock() < 0) variant.setStock(0);
        validateMoneyField(variant.getDisplayRate(), "Display rate");
        validateMoneyField(variant.getPrintedMrp(), "Printed MRP");
        validateMoneyField(variant.getCostPrice(), "Cost price");
        validateMoneyField(variant.getAmountYouWillReceive(), "Amount you will receive");
        validateMoneyField(variant.getCourierCharges(), "Courier charges");
        validateMoneyField(variant.getCodCharge(), "COD charge");
        validatePercentageField(variant.getCommissionPercentage(), "Commission percentage");
        validatePercentageField(variant.getGstPercentage(), "GST percentage");
        synchronizeDiscountFromPrices(variant);
        if (variant.getMinimumPartialPaymentPercentage() != null
                && (!Double.isFinite(variant.getMinimumPartialPaymentPercentage())
                || variant.getMinimumPartialPaymentPercentage() < 10
                || variant.getMinimumPartialPaymentPercentage() > 99)) {
            throw badRequest("Minimum partial payment percentage must be from 10 through 99");
        }
        try {
            Math.multiplyExact(variant.getPackSize().longValue(), variant.getPackQuantity().longValue());
        } catch (ArithmeticException exception) {
            throw badRequest("Combined package quantity is too large");
        }
    }

    private void synchronizeDiscountFromPrices(ProductVariant variant) {
        Double mrpValue = variant.getPrintedMrp();
        Double displayValue = variant.getDisplayRate();
        if (mrpValue == null || displayValue == null) {
            variant.setDiscountOnMrpRs(null);
            variant.setDiscountPercentage(null);
            return;
        }

        BigDecimal mrp = BigDecimal.valueOf(mrpValue).setScale(2, RoundingMode.HALF_UP);
        BigDecimal displayRate = BigDecimal.valueOf(displayValue).setScale(2, RoundingMode.HALF_UP);
        if (displayRate.compareTo(mrp) > 0) {
            throw badRequest("Display rate cannot be greater than printed MRP");
        }
        variant.setPrintedMrp(mrp.doubleValue());
        variant.setDisplayRate(displayRate.doubleValue());

        BigDecimal discountAmount = mrp.subtract(displayRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal discountPercentage = mrp.signum() == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : discountAmount.multiply(BigDecimal.valueOf(100))
                        .divide(mrp, 2, RoundingMode.HALF_UP);
        variant.setDiscountOnMrpRs(discountAmount.doubleValue());
        variant.setDiscountPercentage(discountPercentage.doubleValue());
    }

    private void copySellerFields(Product source, Product target, Product existing) {
        target.setSku(existing != null ? existing.getSku()
                : (target.getSku() != null ? target.getSku() : source.getSku()));
        target.setHsnCode(source.getHsnCode());
        target.setTitle(source.getTitle().trim());
        target.setBrandName(source.getBrandName() != null ? source.getBrandName().trim() : null);
        target.setTechnicalName(source.getTechnicalName());
        target.setDescription(source.getDescription());
        target.setProductImages(
                existing == null ? List.of() : existing.getProductImages());
        target.setCategoryId(source.getCategoryId());
        target.setAssignedCollections(existing == null ? List.of()
                : normalizeAssignedCollections(existing.getAssignedCollections()));
        target.setRefundPolicy(source.getRefundPolicy()); target.setProductWeight(source.getProductWeight());
        target.setProductWeightUnit(source.getProductWeightUnit() == null ? null : source.getProductWeightUnit().trim().toLowerCase(Locale.ROOT));
        List<ProductVariant> oldVariants = existing == null || existing.getVariants() == null ? List.of() : existing.getVariants();
        String baseSku = existing == null ? target.getSku() : existing.getSku();
        target.setVariants(sanitizeVariants(source.getVariants(), oldVariants, baseSku));
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
            boolean retain = prior != null && retainedIds.add(prior.getId().toLowerCase(Locale.ROOT));
            ProductVariant clean = new ProductVariant();
            clean.setId(retain ? prior.getId() : (input.getId() != null && ObjectId.isValid(input.getId()) ? input.getId() : new ObjectId().toHexString()));
            clean.setSku(prior != null ? prior.getSku() : null);
            if (clean.getSku() == null || clean.getSku().isBlank()) {
                clean.setSku(SellerCodeService.variantSku(productSku, nextVariantNumber(currentList, result)));
            }
            clean.setLabel(input.getLabel() != null && !input.getLabel().isBlank() ? input.getLabel() : "Variant " + (result.size() + 1));
            String unit = input.getUnit() == null ? (input.getPackSizeUnit() == null ? "ml" : input.getPackSizeUnit().trim().toLowerCase(Locale.ROOT)) : input.getUnit().trim().toLowerCase(Locale.ROOT);
            clean.setUnit(unit.isBlank() ? "ml" : unit);
            clean.setPackSize(input.getPackSize() != null && input.getPackSize() > 0 ? input.getPackSize() : 1);
            clean.setPackSizeUnit(input.getPackSizeUnit() != null && !input.getPackSizeUnit().isBlank() ? input.getPackSizeUnit().trim().toLowerCase(Locale.ROOT) : clean.getUnit());
            clean.setPackQuantity(input.getPackQuantity() != null && input.getPackQuantity() > 0 ? input.getPackQuantity() : 1);
            clean.setShippedBy(input.getShippedBy() == null || input.getShippedBy().isBlank() ? "essentialbiocropscience" : input.getShippedBy().trim());
            clean.setSwg(input.getSwg());
            clean.setDimensions(input.getDimensions() == null || input.getDimensions().isEmpty()
                    ? List.of(new ProductDimensions(20.0, 10.0, 10.0, "cm"))
                    : normalizeDimensions(input.getDimensions()));

            clean.setDisplayRate(input.getDisplayRate());
            clean.setPrintedMrp(input.getPrintedMrp());
            clean.setCostPrice(input.getCostPrice());
            clean.setAmountYouWillReceive(input.getAmountYouWillReceive());
            clean.setCommissionPercentage(input.getCommissionPercentage());
            clean.setCourierCharges(input.getCourierCharges());
            clean.setGstPercentage(input.getGstPercentage());
            clean.setDiscountOnMrpRs(input.getDiscountOnMrpRs());
            clean.setDiscountPercentage(input.getDiscountPercentage());
            clean.setPaymentMethod(input.getPaymentMethod());
            clean.setIsPrepaidAllowed(input.getIsPrepaidAllowed());
            clean.setIsPartialPaymentAllowed(input.getIsPartialPaymentAllowed());
            clean.setMinimumPartialPaymentPercentage(input.getMinimumPartialPaymentPercentage());
            clean.setCodCharge(input.getCodCharge());
            clean.setStock(input.getStock() != null && input.getStock() >= 0 ? input.getStock() : 0);
            clean.setDisplayOrder(input.getDisplayOrder() != null ? input.getDisplayOrder() : (result.size() + 1));
            clean.setIsDefault(input.getIsDefault());
            result.add(clean);
        }
        return List.copyOf(result);
    }

    private int nextVariantNumber(List<ProductVariant> existing, List<ProductVariant> assigned) {
        int max = 0;
        for (ProductVariant variant : existing) max = Math.max(max, variantNumber(variant.getSku()));
        for (ProductVariant variant : assigned) max = Math.max(max, variantNumber(variant.getSku()));
        return max + 1;
    }

    private void copyVariantPricingSettings(ProductVariant source, ProductVariant target) {
        target.setAmountYouWillReceive(source.getAmountYouWillReceive());
        target.setCommissionPercentage(source.getCommissionPercentage());
        target.setCourierCharges(source.getCourierCharges());
        target.setPaymentMethod(source.getPaymentMethod());
        target.setIsPrepaidAllowed(source.getIsPrepaidAllowed());
        target.setIsPartialPaymentAllowed(source.getIsPartialPaymentAllowed());
        target.setMinimumPartialPaymentPercentage(source.getMinimumPartialPaymentPercentage());
        target.setCodCharge(source.getCodCharge());
    }

    private int variantNumber(String sku) {
        if (sku == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("-V(\\d+)$").matcher(sku);
        if (!matcher.find()) return 0;
        try { return Integer.parseInt(matcher.group(1)); } catch (NumberFormatException ignored) { return 0; }
    }

    private AppException badRequest(String message) { return new AppException(message, HttpStatus.BAD_REQUEST); }
    private boolean positive(Double value) { return value != null && Double.isFinite(value) && value > 0; }
    private boolean finiteNonnegative(Double value) { return value != null && Double.isFinite(value) && value >= 0; }
    private void validateMoneyField(Double value, String label) {
        if (value != null && !finiteNonnegative(value)) throw badRequest(label + " must be a finite nonnegative number");
    }
    private void validatePercentageField(Double value, String label) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 100)) {
            throw badRequest(label + " must be between 0 and 100");
        }
    }
    private List<ProductDimensions> normalizeDimensions(List<ProductDimensions> values) {
        if (values == null) return List.of();
        return values.stream().filter(java.util.Objects::nonNull)
                .map(value -> new ProductDimensions(value.getLength(), value.getWidth(), value.getHeight(), "cm"))
                .toList();
    }

    private static class UserSequence {
        private Long productCodeSequence;
        public Long getProductCodeSequence() { return productCodeSequence; }
        public void setProductCodeSequence(Long productCodeSequence) { this.productCodeSequence = productCodeSequence; }
    }
}
