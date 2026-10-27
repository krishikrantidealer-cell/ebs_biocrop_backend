package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.request.SellerProductWriteRequest;
import com.ebs.biocrop.entity.Product;
import org.springframework.data.domain.Page;

public interface SellerProductService {
    Page<Product> list(String sellerId, int page, int size);
    Product get(String sellerId, String id);
    Product create(String sellerId, SellerProductWriteRequest request);
    Product update(String sellerId, String id, SellerProductWriteRequest request);
    void validateProduct(Product product);
}
