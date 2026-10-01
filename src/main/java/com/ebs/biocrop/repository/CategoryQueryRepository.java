package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface CategoryQueryRepository {
    Page<Category> findPublicPage(String parentId, Integer level, Pageable pageable);
    Page<Category> findAdminPage(String parentId, Integer level, Pageable pageable);
    List<Category> findChildren(String parentId, int limit);
    boolean hasActiveChildren(String parentId);
    boolean existsSlugUnderParent(String parentId, String slug, String excludeId);
}
