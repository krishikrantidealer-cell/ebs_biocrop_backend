package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String ACTIVE_STATUS = "ACTIVE";

    private final ProductRepository productRepository;
    private final MongoTemplate mongoTemplate;

    public ProductController(ProductRepository productRepository, MongoTemplate mongoTemplate) {
        this.productRepository = productRepository;
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<Product>>> getProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok("Products retrieved successfully", productRepository
                .findByStatusIgnoreCaseOrStatusIsNull(ACTIVE_STATUS, pageable(page, size))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Product>> getProductById(@PathVariable String id) {
        Product product = productRepository.findById(id)
                .filter(p -> isActive(p.getStatus()))
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));
        return ResponseEntity.ok(ApiResponse.ok("Product retrieved successfully", product));
    }

    @GetMapping("/category/{categoryId}")
    public ResponseEntity<ApiResponse<Page<Product>>> getProductsByCategory(
            @PathVariable String categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = pageable(page, size);
        Page<Product> products = productRepository.findByStatusIgnoreCaseAndCategoryIdOrStatusIsNullAndCategoryId(
                ACTIVE_STATUS, categoryId, categoryId, pageable);
        if (products.isEmpty() && page == 0) {
            products = productRepository.findByStatusIgnoreCaseAndCategoryOrStatusIsNullAndCategory(
                    ACTIVE_STATUS, categoryId, categoryId, pageable);
        }
        return ResponseEntity.ok(ApiResponse.ok("Products retrieved successfully", products));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<Page<Product>>> searchProducts(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String query = q.trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }
        Pageable pageable = pageable(page, size);
        Criteria active = new Criteria().orOperator(
                Criteria.where("status").regex("^ACTIVE$", "i"),
                Criteria.where("status").is(null));
        Criteria text = new Criteria().orOperator(
                Criteria.where("title").regex(java.util.regex.Pattern.quote(query), "i"),
                Criteria.where("description").regex(java.util.regex.Pattern.quote(query), "i"));
        Criteria filter = new Criteria().andOperator(active, text);
        Query mongoQuery = Query.query(filter).with(pageable);
        long total = mongoTemplate.count(Query.query(filter), Product.class);
        Page<Product> products = org.springframework.data.support.PageableExecutionUtils
                .getPage(mongoTemplate.find(mongoQuery, Product.class), pageable, () -> total);
        return ResponseEntity.ok(ApiResponse.ok("Products retrieved successfully", products));
    }

    private Pageable pageable(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("Page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "order").and(Sort.by("title")));
    }

    private boolean isActive(String status) {
        // Older imported catalog documents have no status field. The bundled source
        // marks these legacy catalog records ACTIVE; keep them visible until migrated.
        return status == null || ACTIVE_STATUS.equalsIgnoreCase(status.trim());
    }
}
