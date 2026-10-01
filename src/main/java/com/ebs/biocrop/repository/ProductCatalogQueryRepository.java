package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;

public interface ProductCatalogQueryRepository {
    Page<Product> findPublicPage(Pageable pageable);
    Page<Product> findPublicByCategoryIds(Collection<String> categoryIds, Pageable pageable);
    Page<Product> searchPublic(String searchText, Pageable pageable);
}
