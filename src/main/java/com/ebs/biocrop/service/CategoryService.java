package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.request.CategoryWriteRequest;
import com.ebs.biocrop.entity.Category;
import org.springframework.data.domain.Page;

public interface CategoryService {
    Page<Category> listPublic(String parentId, Integer level, int page, int size);
    Category getPublic(String id);
    Page<Category> listAdmin(String parentId, Integer level, int page, int size);
    Category create(CategoryWriteRequest request);
    Category update(String id, CategoryWriteRequest request);
    Category deactivate(String id);
}
