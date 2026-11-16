package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.VariantLabel;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface VariantLabelRepository extends MongoRepository<VariantLabel, String> {

    List<VariantLabel> findAllByOrderByLabelAsc();

    Optional<VariantLabel> findByNormalizedLabel(String normalizedLabel);
}
