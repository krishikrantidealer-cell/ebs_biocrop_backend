package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Optional;
import java.util.List;

@Repository
public interface ProductRepository extends MongoRepository<Product, String>, ProductCatalogQueryRepository {
    @Query(value = "{ 'sellerId': ?0, '$or': [ { 'isDeleted': false }, { 'isDeleted': null }, { 'isDeleted': { '$exists': false } } ] }")
    Page<Product> findActiveBySellerId(String sellerId, Pageable pageable);
    boolean existsBySkuIgnoreCase(String sku);
    Optional<Product> findByProductCode(String productCode);
    Optional<Product> findBySku(String sku);
    Optional<Product> findByIdAndSellerId(String id, String sellerId);
    List<Product> findBySellerId(String sellerId);

    Page<Product> findByStatusIgnoreCase(String status, Pageable pageable);
}
