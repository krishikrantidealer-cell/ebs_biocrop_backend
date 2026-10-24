package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.request.CategoryWriteRequest;
import com.ebs.biocrop.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface CategoryService {
    Page<Category> listPublic(String parentId, Integer level, int page, int size);
    Category getPublic(String id);
    Page<Category> listAdmin(String parentId, Integer level, Boolean active, int page, int size);
    Category getAdminById(String id);
    Category create(CategoryWriteRequest request);
    Category update(String id, CategoryWriteRequest request);
    Category deactivate(String id);
    List<String> permanentlyDelete(String id);
    List<String> permanentlyDeleteMany(List<String> ids);
    Category uploadImage(String id, MultipartFile file);
    Category deleteImage(String id);
}
