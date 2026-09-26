package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends MongoRepository<Product, String> {
    Optional<Product> findByVariantsVariationCode(String variationCode);

    List<Product> findByProductCode(String productCode);

    Page<Product> findByStatusIgnoreCaseOrStatusIsNull(String status, Pageable pageable);

    Page<Product> findByStatusIgnoreCaseAndCategoryIdOrStatusIsNullAndCategoryId(
            String activeStatus, String activeCategoryId, String legacyCategoryId, Pageable pageable);

    Page<Product> findByStatusIgnoreCaseAndCategoryOrStatusIsNullAndCategory(
            String activeStatus, String activeCategory, String legacyCategory, Pageable pageable);

    boolean existsByVariantsVariationCode(String variationCode);
}
