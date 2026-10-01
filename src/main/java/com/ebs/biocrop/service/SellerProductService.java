package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.dto.request.SellerProductWriteRequest;
import org.springframework.data.domain.Page;

public interface SellerProductService {
    Product create(String sellerId, SellerProductWriteRequest request);
    Page<Product> list(String sellerId, int page, int size);
    Product update(String sellerId, String id, SellerProductWriteRequest request);
}
