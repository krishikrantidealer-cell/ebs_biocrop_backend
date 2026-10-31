package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.dto.request.ProductCollectionAssignmentRequest;
import com.ebs.biocrop.dto.request.AdminProductWriteRequest;
import com.ebs.biocrop.dto.request.AdminProductPatchRequest;
import com.ebs.biocrop.dto.request.AdminProductUpdateRequest;
import com.ebs.biocrop.dto.request.AdminProductVariantPatchRequest;
import com.ebs.biocrop.dto.request.AdminProductVariantWriteRequest;
import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface AdminProductService {
    default Page<Product> list(String status, String sellerId, String categoryId, String query, Boolean deleted, int page, int size) {
        return list(status, sellerId, categoryId, query, deleted, null, page, size);
    }
    Page<Product> list(String status, String sellerId, String categoryId, String query, Boolean deleted, Boolean available, int page, int size);
    Product getById(String id);
    Product create(String adminId, AdminProductWriteRequest request);
    Product create(String adminId, AdminProductWriteRequest request, List<MultipartFile> files);
    Product update(String id, AdminProductUpdateRequest request);
    Product update(String id, AdminProductWriteRequest request);
    Product patch(String id, AdminProductPatchRequest request);
    Product addVariant(String productId, AdminProductVariantWriteRequest request);
    Product updateVariant(String productId, String variantId, AdminProductVariantWriteRequest request);
    Product patchVariant(String productId, String variantId, AdminProductVariantPatchRequest request);
    Product setDefaultVariant(String productId, String variantId, boolean isDefault);
    Product deleteVariant(String productId, String variantId);
    Product softDelete(String id);
    Product restore(String id);
    void permanentlyDelete(String id);
    List<String> permanentlyDeleteMany(List<String> ids);
    Product review(String id, boolean approved);
    Product setAvailability(String id, boolean available);
    Product setFeatured(String id, boolean featured);
    Product assignCollections(String productId, ProductCollectionAssignmentRequest request);
    Product addImages(String productId, List<MultipartFile> files);
    Product deleteImages(String productId, List<String> imageIds);
}
