package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Product;
import org.springframework.data.domain.Page;

public interface ProductCatalogService {
    Page<Product> listPublic(int page, int size);
    Product getPublic(String id);
    Page<Product> listByCategory(String categoryId, int page, int size);
    Page<Product> search(String query, int page, int size);
}
