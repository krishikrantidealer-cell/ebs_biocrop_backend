package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.dto.request.ProductCollectionAssignmentRequest;
import com.ebs.biocrop.dto.request.AdminProductWriteRequest;
import com.ebs.biocrop.dto.request.AdminProductPatchRequest;
import com.ebs.biocrop.dto.request.AdminProductVariantPatchRequest;
import com.ebs.biocrop.dto.request.AdminProductVariantWriteRequest;
import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface AdminProductService {
    Page<Product> listAdminProducts(String status, String sellerId, String categoryId, String query, Boolean deleted, int page, int size);
    Product getAdminProductById(String id);
    Product createAdminProduct(String adminId, AdminProductWriteRequest request);
    Product updateAdminProduct(String id, AdminProductWriteRequest request);
    Product patchAdminProduct(String id, AdminProductPatchRequest request);
    Product addAdminProductVariant(String productId, AdminProductVariantWriteRequest request);
    Product updateAdminProductVariant(String productId, String variantId, AdminProductVariantWriteRequest request);
    Product patchAdminProductVariant(String productId, String variantId, AdminProductVariantPatchRequest request);
    Product deleteAdminProductVariant(String productId, String variantId);
    Product softDeleteAdminProduct(String id);
    Product restoreAdminProduct(String id);
    void permanentlyDeleteAdminProduct(String id);
    Product reviewAdminProduct(String id, boolean approved);
    Product setAdminProductAvailability(String id, boolean available);
    Product setAdminProductFeatured(String id, boolean featured);
    Product assignAdminProductCollections(String productId, ProductCollectionAssignmentRequest request);
    Product addAdminProductImages(String productId, List<MultipartFile> files);
    Product deleteAdminProductImage(String productId, String imageId);
    Product deleteAdminProductImages(String productId, List<String> imageIds);
}
