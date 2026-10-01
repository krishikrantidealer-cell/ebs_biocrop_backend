package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends MongoRepository<Product, String>, ProductCatalogQueryRepository {
    Page<Product> findBySellerId(String sellerId, Pageable pageable);
    boolean existsBySkuIgnoreCase(String sku);
    Optional<Product> findByIdAndSellerId(String id, String sellerId);

    Page<Product> findByStatusIgnoreCase(String status, Pageable pageable);
}
