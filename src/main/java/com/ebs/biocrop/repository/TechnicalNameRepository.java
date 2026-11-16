package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.TechnicalName;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TechnicalNameRepository extends MongoRepository<TechnicalName, String> {

    Optional<TechnicalName> findByNormalizedName(String normalizedName);

    List<TechnicalName> findAllByOrderByNameAsc();

    List<TechnicalName> findByIsActiveTrueOrderByNameAsc();
}
