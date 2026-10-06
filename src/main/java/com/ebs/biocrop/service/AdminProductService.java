package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface AdminProductService {
    Page<Product> listForReview(String status, int page, int size);
    Product review(String id, boolean approved);
    Product setAvailability(String id, boolean available);
    Product setFeatured(String id, boolean featured);
    Product addImages(String productId, List<MultipartFile> files);
    Product deleteImage(String productId, String imageId);
    Product deleteImages(String productId, List<String> imageIds);
}
