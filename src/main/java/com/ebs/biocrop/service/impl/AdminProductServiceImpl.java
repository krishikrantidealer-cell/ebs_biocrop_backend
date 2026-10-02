package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.service.AdminProductService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.service.ProductImageVariantService;
import com.ebs.biocrop.entity.ProductImage;


@Service
public class AdminProductServiceImpl implements AdminProductService {
    private static final Set<String> ALLOWED_STATUSES = Set.of("PENDING_REVIEW", "ACTIVE", "REJECTED", "INACTIVE");
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final GcsImageStorageService imageStorage;
    private final ProductImageVariantService imageVariants;

    public AdminProductServiceImpl(
            ProductRepository products,
            CategoryRepository categories,
            GcsImageStorageService imageStorage,
            ProductImageVariantService imageVariants) {
        this.products = products;
        this.categories = categories;
        this.imageStorage = imageStorage;
        this.imageVariants = imageVariants;
    }

    @Override
    public Page<Product> listForReview(String status, int page, int size) {
        String normalized = status == null || status.isBlank() ? "PENDING_REVIEW" : status.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new AppException("Unsupported product status filter", HttpStatus.BAD_REQUEST);
        }
        return products.findByStatusIgnoreCase(normalized,
                PageRequestSupport.create(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
    }

    @Override
    public Product review(String id, boolean approved) {
        Product product = findProduct(id);
        if (approved) {
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
        }
        product.setStatus(approved ? "ACTIVE" : "REJECTED");
        product.setIsAvailable(approved);
        product.setUpdatedAt(LocalDateTime.now());
        return products.save(product);
    }

    @Override
    public Product setAvailability(String id, boolean available) {
        Product product = findProduct(id);
        if (available && !"ACTIVE".equalsIgnoreCase(product.getStatus())) {
            throw new AppException("Only an approved product can be made available", HttpStatus.CONFLICT);
        }
        product.setIsAvailable(available);
        product.setUpdatedAt(LocalDateTime.now());
        return products.save(product);
    }

    @Override
    public Product setFeatured(String id, boolean featured) {
        Product product = findProduct(id);
        if (featured && (!"ACTIVE".equalsIgnoreCase(product.getStatus())
                || !Boolean.TRUE.equals(product.getIsAvailable()))) {
            throw new AppException("Only an active, available product can be featured", HttpStatus.CONFLICT);
        }
        product.setIsFeatured(featured);
        product.setUpdatedAt(LocalDateTime.now());
        return products.save(product);
    }

    @Override
    public Product addImage(String productId, byte[] imageBytes) {
        Product product = findProduct(productId);

        if (product.getSellerId() == null || product.getSellerId().isBlank()) {
            throw new AppException(
                    "Product must have a seller before images can be uploaded",
                    HttpStatus.CONFLICT);
        }

        ProductImageVariantService.Variants variants =
                imageVariants.createVariants(imageBytes);

        int displayOrder = product.getProductImages() == null
                ? 0
                : product.getProductImages().size();

        ProductImage image = imageStorage.uploadImage(
                product.getSellerId(),
                product.getId(),
                variants,
                displayOrder);

        if (product.getProductImages() == null) {
            product.setProductImages(new java.util.ArrayList<>());
        }
        product.getProductImages().add(image);
        product.setUpdatedAt(LocalDateTime.now());

        try {
            return products.save(product);
        } catch (RuntimeException saveFailure) {
            try {
                imageStorage.deleteImageVariants(
                        product.getSellerId(),
                        product.getId(),
                        image);
            } catch (RuntimeException cleanupFailure) {
                saveFailure.addSuppressed(cleanupFailure);
            }
            throw saveFailure;
        }
    }

    @Override
    public Product deleteImage(String productId, String imageId) {
        Product product = findProduct(productId);

        if (product.getProductImages() == null || product.getProductImages().isEmpty()) {
            throw new ResourceNotFoundException("Product image", "id", imageId);
        }

        ProductImage image = product.getProductImages().stream()
                .filter(item -> imageId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() ->
                        new ResourceNotFoundException("Product image", "id", imageId));

        product.getProductImages().remove(image);
        product.setUpdatedAt(LocalDateTime.now());

        Product saved = products.save(product);

        // Delete from GCS only after MongoDB no longer references the image.
        imageStorage.deleteImageVariants(
                product.getSellerId(),
                product.getId(),
                image);

        return saved;
    }

    private Product findProduct(String id) {
        return products.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));
    }
}
