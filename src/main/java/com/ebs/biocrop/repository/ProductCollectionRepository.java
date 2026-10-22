package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.ProductCollection;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ProductCollectionRepository extends MongoRepository<ProductCollection, String> {
    List<ProductCollection> findByIsActiveTrueOrderByPriorityAscNameAsc();
    Optional<ProductCollection> findBySlugIgnoreCaseAndIsActiveTrue(String slug);
    boolean existsBySlugIgnoreCase(String slug);
    boolean existsBySlugIgnoreCaseAndIdNot(String slug, String id);
}
