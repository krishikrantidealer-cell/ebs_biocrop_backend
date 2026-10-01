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

@Service
public class AdminProductServiceImpl implements AdminProductService {
    private static final Set<String> ALLOWED_STATUSES = Set.of("PENDING_REVIEW", "ACTIVE", "REJECTED", "INACTIVE");
    private final ProductRepository products;
    private final CategoryRepository categories;

    public AdminProductServiceImpl(ProductRepository products, CategoryRepository categories) {
        this.products = products;
        this.categories = categories;
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

    private Product findProduct(String id) {
        return products.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));
    }
}
