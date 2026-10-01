package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.service.ProductCatalogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class ProductCatalogServiceImpl implements ProductCatalogService {
    private static final int MAX_SEARCH_LENGTH = 100;
    private static final Sort PRODUCT_ORDER = Sort.by(Sort.Direction.ASC, "title");

    private final ProductRepository products;
    private final CategoryRepository categories;

    public ProductCatalogServiceImpl(ProductRepository products, CategoryRepository categories) {
        this.products = products;
        this.categories = categories;
    }

    @Override
    public Page<Product> listPublic(int page, int size) {
        return products.findPublicPage(PageRequestSupport.create(page, size, PRODUCT_ORDER));
    }

    @Override
    public Product getPublic(String id) {
        return products.findById(id).filter(this::isPubliclyAvailable)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));
    }

    @Override
    public Page<Product> listByCategory(String categoryId, int page, int size) {
        Category root = categories.findById(categoryId)
                .filter(category -> !Boolean.FALSE.equals(category.getIsActive()))
                .filter(category -> category.getLevel() != null && category.getLevel() >= 0 && category.getLevel() <= 2)
                .orElseThrow(() -> new ResourceNotFoundException("Category", "id", categoryId));
        Set<String> categoryIds = new LinkedHashSet<>();
        collectActiveSubtree(root, categoryIds);
        return products.findPublicByCategoryIds(categoryIds, PageRequestSupport.create(page, size, PRODUCT_ORDER));
    }

    @Override
    public Page<Product> search(String query, int page, int size) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            throw new AppException("Search query must not be blank", HttpStatus.BAD_REQUEST);
        }
        if (normalized.length() > MAX_SEARCH_LENGTH) {
            throw new AppException("Search query must be at most " + MAX_SEARCH_LENGTH + " characters", HttpStatus.BAD_REQUEST);
        }
        return products.searchPublic(normalized, PageRequestSupport.create(page, size, PRODUCT_ORDER));
    }

    private void collectActiveSubtree(Category parent, Set<String> categoryIds) {
        categoryIds.add(parent.getId());
        if (parent.getLevel() >= 2) return;
        for (Category child : categories.findChildren(parent.getId(), 100)) {
            if (!Boolean.FALSE.equals(child.getIsActive())
                    && Integer.valueOf(parent.getLevel() + 1).equals(child.getLevel())) {
                collectActiveSubtree(child, categoryIds);
            }
        }
    }

    private boolean isPubliclyAvailable(Product product) {
        return product.getStatus() != null && "ACTIVE".equalsIgnoreCase(product.getStatus().trim())
                && product.getSellerId() != null && !product.getSellerId().isBlank()
                && Boolean.TRUE.equals(product.getIsAvailable());
    }
}
