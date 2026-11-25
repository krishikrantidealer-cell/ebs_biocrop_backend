package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.HeroBanner;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface HeroBannerRepository extends MongoRepository<HeroBanner, String> {
}
