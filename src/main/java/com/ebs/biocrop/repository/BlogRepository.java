package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.Blog;
import com.ebs.biocrop.entity.enums.BlogStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BlogRepository extends MongoRepository<Blog, String> {
    Optional<Blog> findBySlugAndStatusAndDeletedAtIsNull(String slug, BlogStatus status);
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, String id);
    List<Blog> findByStatusAndScheduledAtLessThanEqualAndDeletedAtIsNull(BlogStatus status, Instant scheduledAt);
}
