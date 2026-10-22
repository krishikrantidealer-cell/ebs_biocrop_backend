package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.dto.request.ProductCollectionAssignmentRequest;
import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface AdminProductService {
    Page<Product> listForReview(String status, int page, int size);
    Product review(String id, boolean approved);
    Product setAvailability(String id, boolean available);
    Product setFeatured(String id, boolean featured);
    Product assignCollections(String productId, ProductCollectionAssignmentRequest request);
    Product addImages(String productId, List<MultipartFile> files);
    Product deleteImage(String productId, String imageId);
    Product deleteImages(String productId, List<String> imageIds);
}
