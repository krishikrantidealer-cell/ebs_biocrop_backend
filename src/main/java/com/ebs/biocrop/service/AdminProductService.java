package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Product;
import org.springframework.data.domain.Page;

public interface AdminProductService {
    Page<Product> listForReview(String status, int page, int size);
    Product review(String id, boolean approved);
    Product setAvailability(String id, boolean available);
    Product setFeatured(String id, boolean featured);
    Product addImage(String productId, byte[] imageBytes);
    Product deleteImage(String productId, String imageId);
}
