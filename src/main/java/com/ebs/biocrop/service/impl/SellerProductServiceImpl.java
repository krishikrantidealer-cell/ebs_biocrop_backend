package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.dto.request.SellerProductWriteRequest;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductDimensions;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.service.SellerProductService;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
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

    public SellerProductServiceImpl(ProductRepository products, CategoryRepository categories) {
        this.products = products;
        this.categories = categories;
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
        listing.setStatus("PENDING_REVIEW");
        listing.setIsAvailable(false);
        listing.setIsFeatured(false);
        listing.setRatings(null);
        listing.setCreatedAt(LocalDateTime.now());
        listing.setUpdatedAt(listing.getCreatedAt());
        return save(listing);
    }

    @Override
    public Page<Product> list(String sellerId, int page, int size) {
        return products.findBySellerId(sellerId,
                PageRequestSupport.create(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
    }

    @Override
    public Product update(String sellerId, String id, SellerProductWriteRequest writeRequest) {
        Product request = writeRequest.toProduct();
        Product current = products.findByIdAndSellerId(id, sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seller product", "id", id));
        validate(request);
        String requestedSku = request.getSku().trim();
        if (products.existsBySkuIgnoreCase(requestedSku) && !requestedSku.equalsIgnoreCase(current.getSku())) {
            throw new AppException("SKU is already in use", HttpStatus.CONFLICT);
        }
        Product listing = new Product();
        copySellerFields(request, listing, current);
        listing.setId(current.getId());
        listing.setSellerId(current.getSellerId());
        listing.setStatus("PENDING_REVIEW");
        listing.setIsAvailable(false);
        listing.setIsFeatured(Boolean.TRUE.equals(current.getIsFeatured()));
        listing.setRatings(current.getRatings());
        listing.setCreatedAt(current.getCreatedAt());
        listing.setUpdatedAt(LocalDateTime.now());
        listing.setVersion(current.getVersion());
        return save(listing);
    }

    private Product save(Product product) {
        try {
            return products.save(product);
        } catch (DuplicateKeyException exception) {
            throw new AppException("SKU is already in use", HttpStatus.CONFLICT);
        }
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

    private void validateVariant(Product product, ProductVariant variant) {
        ProductDimensions dimensions = variant.getDimensions() == null ? product.getDimensions() : variant.getDimensions();
        if (dimensions == null) throw badRequest("Product dimensions are required");
        if (!"cm".equalsIgnoreCase(dimensions.getUnit())) throw badRequest("Variant dimensions must use centimeters (cm)");
        if (!positive(dimensions.getLength()) || !positive(dimensions.getWidth()) || !positive(dimensions.getHeight())) {
            throw badRequest("Variant length, width, and height must be positive");
        }
        if (variant.getPackSize() == null || variant.getPackSize() <= 0
                || variant.getPackSizeUnit() == null || variant.getPackSizeUnit().isBlank()) {
            throw badRequest("Each variant needs a positive pack size and unit");
        }
        if (variant.getPackQuantity() == null || variant.getPackQuantity() <= 0) throw badRequest("Each variant needs a positive pack quantity");
        if (variant.getUnit() != null && variant.getUnit().isBlank()) throw badRequest("Variant unit cannot be blank");
        if (variant.getSwg() != null && !finiteNonnegative(variant.getSwg())) throw badRequest("SWG must be a finite nonnegative number");
        if (!finiteNonnegative(variant.getDisplayRate())) throw badRequest("Each variant needs a nonnegative display rate");
        if (variant.getStock() == null || variant.getStock() < 0) throw badRequest("Each variant needs stock equal to or greater than zero");
        if (variant.getPrintedMrp() != null && (!finiteNonnegative(variant.getPrintedMrp()) || variant.getPrintedMrp() < variant.getDisplayRate())) {
            throw badRequest("Printed MRP cannot be lower than display rate");
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
        target.setProductCode(source.getProductCode()); target.setHsnCode(source.getHsnCode());
        target.setTitle(source.getTitle().trim()); target.setTechnicalName(source.getTechnicalName());
        target.setVendor(source.getVendor()); target.setDescription(source.getDescription());
        target.setImages(source.getImages()); target.setTechnicalContent(source.getTechnicalContent());
        target.setProductImages(
                existing == null ? List.of() : existing.getProductImages());
        target.setFeatures(source.getFeatures()); target.setBenefits(source.getBenefits());
        target.setModeOfAction(source.getModeOfAction()); target.setSuitableCrops(source.getSuitableCrops());
        target.setTargetPests(source.getTargetPests()); target.setTargetDiseases(source.getTargetDiseases());
        target.setDosage(source.getDosage()); target.setApplicationMethod(source.getApplicationMethod());
        target.setCategoryId(source.getCategoryId()); target.setCollectionIds(source.getCollectionIds());
        target.setSubCollectionIds(source.getSubCollectionIds()); target.setDimensions(normalizeDimensions(source.getDimensions()));
        target.setRefundPolicy(source.getRefundPolicy()); target.setProductWeight(source.getProductWeight());
        target.setProductWeightUnit(source.getProductWeightUnit() == null ? null : source.getProductWeightUnit().trim().toLowerCase(Locale.ROOT));
        List<ProductVariant> oldVariants = existing == null || existing.getVariants() == null ? List.of() : existing.getVariants();
        target.setVariants(sanitizeVariants(source.getVariants(), oldVariants));
    }

    private List<ProductVariant> sanitizeVariants(List<ProductVariant> submitted, List<ProductVariant> current) {
        Set<String> retainedIds = new HashSet<>();
        List<ProductVariant> result = new ArrayList<>();
        for (ProductVariant input : submitted) {
            ProductVariant prior = current.stream().filter(value -> value.getId() != null && input.getId() != null
                    && value.getId().equalsIgnoreCase(input.getId())).findFirst().orElse(null);
            boolean retain = prior != null && retainedIds.add(prior.getId().toLowerCase(Locale.ROOT));
            ProductVariant clean = new ProductVariant();
            clean.setId(retain ? prior.getId() : new ObjectId().toHexString());
            clean.setLabel(input.getLabel());
            clean.setUnit(input.getUnit() == null ? input.getPackSizeUnit().trim().toLowerCase(Locale.ROOT) : input.getUnit().trim().toLowerCase(Locale.ROOT));
            clean.setPackSize(input.getPackSize()); clean.setPackSizeUnit(input.getPackSizeUnit().trim().toLowerCase(Locale.ROOT));
            clean.setPackQuantity(input.getPackQuantity());
            clean.setPackUnit(input.getPackUnit() == null ? null : input.getPackUnit().trim().toLowerCase(Locale.ROOT));
            clean.setShippedBy(input.getShippedBy() == null ? null : input.getShippedBy().trim());
            clean.setSwg(input.getSwg());
            clean.setTotalBaseQuantity(Math.multiplyExact(input.getPackSize().longValue(), input.getPackQuantity().longValue()));
            clean.setTotalBaseUnit(input.getPackSizeUnit().trim().toLowerCase(Locale.ROOT));
            clean.setDimensions(input.getDimensions() == null ? null : normalizeDimensions(input.getDimensions()));
            clean.setDisplayRate(input.getDisplayRate()); clean.setPrintedMrp(input.getPrintedMrp());
            clean.setCostPrice(input.getCostPrice()); clean.setDiscountRs(input.getDiscountRs());
            clean.setGstPercentage(input.getGstPercentage()); clean.setDiscountPercentage(input.getDiscountPercentage());
            clean.setStock(input.getStock()); clean.setDisplayOrder(input.getDisplayOrder()); clean.setIsDefault(input.getIsDefault());
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
}
