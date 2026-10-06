package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.service.AdminProductService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.multipart.MultipartFile;
import com.ebs.biocrop.service.GcsImageStorageService;
import com.ebs.biocrop.service.ProductImageVariantService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.entity.ProductImage;


@Service
public class AdminProductServiceImpl implements AdminProductService {
    private static final Set<String> ALLOWED_STATUSES = Set.of("PENDING_REVIEW", "ACTIVE", "REJECTED", "INACTIVE");
    private static final int MAX_PRODUCT_IMAGES = 6;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final GcsImageStorageService imageStorage;
    private final ProductImageVariantService imageVariants;
    private final RedisJsonCache redisCache;

    public AdminProductServiceImpl(
            ProductRepository products,
            CategoryRepository categories,
            GcsImageStorageService imageStorage,
            ProductImageVariantService imageVariants,
            RedisJsonCache redisCache) {
        this.products = products;
        this.categories = categories;
        this.imageStorage = imageStorage;
        this.imageVariants = imageVariants;
        this.redisCache = redisCache;
    }

    @Override
    public Page<Product> listForReview(String status, int page, int size) {
        String normalized = status == null || status.isBlank() ? "PENDING_REVIEW" : status.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new AppException("Unsupported product status filter", HttpStatus.BAD_REQUEST);
        }
        return products.findByStatusIgnoreCase(normalized,
                PageRequestSupport.create(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
    }

    @Override
    public Product review(String id, boolean approved) {
        Product product = findProduct(id);
        if (approved) {
            if (product.getProductImages() == null || product.getProductImages().isEmpty()) {
                throw new AppException(
                        "At least one product image is required before approval",
                        HttpStatus.CONFLICT);
            }
            if (product.getSellerId() == null || product.getSellerId().isBlank()) {
                throw new AppException("Only seller-owned listings can be approved", HttpStatus.CONFLICT);
            }
            Category category = product.getCategoryId() == null ? null : categories.findById(product.getCategoryId())
                    .filter(item -> !Boolean.FALSE.equals(item.getIsActive()))
                    .filter(item -> item.getLevel() != null && item.getLevel() >= 0 && item.getLevel() <= 2)
                    .orElse(null);
            if (category == null) {
                throw new AppException("Product category is missing, inactive, or outside the supported depth", HttpStatus.CONFLICT);
            }
            boolean hasChildren = categories.hasActiveChildren(category.getId());
            if (hasChildren) {
                throw new AppException("Products must use a subcategory or a category without children", HttpStatus.CONFLICT);
            }
        }
        product.setStatus(approved ? "ACTIVE" : "REJECTED");
        product.setIsAvailable(approved);
        product.setUpdatedAt(LocalDateTime.now());
        return saveAndInvalidatePublicCatalog(product);
    }

    @Override
    public Product setAvailability(String id, boolean available) {
        Product product = findProduct(id);
        if (available && !"ACTIVE".equalsIgnoreCase(product.getStatus())) {
            throw new AppException("Only an approved product can be made available", HttpStatus.CONFLICT);
        }
        product.setIsAvailable(available);
        product.setUpdatedAt(LocalDateTime.now());
        return saveAndInvalidatePublicCatalog(product);
    }

    @Override
    public Product setFeatured(String id, boolean featured) {
        Product product = findProduct(id);
        if (featured && (!"ACTIVE".equalsIgnoreCase(product.getStatus())
                || !Boolean.TRUE.equals(product.getIsAvailable()))) {
            throw new AppException("Only an active, available product can be featured", HttpStatus.CONFLICT);
        }
        product.setIsFeatured(featured);
        product.setUpdatedAt(LocalDateTime.now());
        return saveAndInvalidatePublicCatalog(product);
    }

    @Override
    public Product addImages(String productId, List<MultipartFile> files) {
        if (files == null || files.isEmpty() || files.size() > MAX_PRODUCT_IMAGES) {
            throw new AppException(
                    "Upload between 1 and 6 product images per request",
                    HttpStatus.BAD_REQUEST);
        }

        Product product = findProduct(productId);

        int currentImageCount = product.getProductImages() == null
                ? 0
                : product.getProductImages().size();
        int remainingSlots = Math.max(0, MAX_PRODUCT_IMAGES - currentImageCount);
        if (files.size() > remainingSlots && remainingSlots > 0) {
            throw new AppException(
                    "This product has only " + remainingSlots + " image slot(s) remaining",
                    HttpStatus.CONFLICT);
        } else if (remainingSlots <= 0) {
            throw new AppException(
                    "This product already has the maximum number of images (" + MAX_PRODUCT_IMAGES + ")",
                    HttpStatus.CONFLICT);
        }


        if (product.getSellerId() == null || product.getSellerId().isBlank()) {
            throw new AppException(
                    "Product must have a seller before images can be uploaded",
                    HttpStatus.CONFLICT);
        }

        if (files.stream().anyMatch(file -> file == null || file.isEmpty())) {
            throw new AppException("Uploaded image files must not be empty", HttpStatus.BAD_REQUEST);
        }

        // Validate the whole batch before writing any object to GCS.
        try {
            for (MultipartFile file : files) {
                imageVariants.validateImage(file.getBytes());
            }
        } catch (IOException readFailure) {
            throw new AppException(
                    "Could not read one of the uploaded image files",
                    HttpStatus.BAD_REQUEST);
        }

        List<ProductImage> uploadedImages = new ArrayList<>();

        try {
            for (MultipartFile file : files) {
                ProductImageVariantService.Variants variants =
                        imageVariants.createVariants(file.getBytes());
                ProductImage image = imageStorage.uploadImage(
                        product.getSellerId(),
                        product.getId(),
                        variants,
                        currentImageCount + uploadedImages.size());
                uploadedImages.add(image);
            }

            if (product.getProductImages() == null) {
                product.setProductImages(new ArrayList<>());
            }
            product.getProductImages().addAll(uploadedImages);
            product.setUpdatedAt(LocalDateTime.now());
            return saveAndInvalidatePublicCatalog(product);
        } catch (IOException readFailure) {
            AppException uploadFailure = new AppException(
                    "Could not read one of the uploaded image files",
                    HttpStatus.BAD_REQUEST);
            cleanupUploadedImages(product, uploadedImages, uploadFailure);
            throw uploadFailure;
        } catch (RuntimeException saveFailure) {
            cleanupUploadedImages(product, uploadedImages, saveFailure);
            throw saveFailure;
        }
    }

    private void cleanupUploadedImages(
            Product product,
            List<ProductImage> uploadedImages,
            RuntimeException failure) {
        for (ProductImage image : uploadedImages) {
            try {
                imageStorage.deleteImageVariants(
                        product.getSellerId(),
                        product.getId(),
                        image);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    @Override
    public Product deleteImage(String productId, String imageId) {
        return deleteImages(productId, List.of(imageId));
    }

    @Override
    public Product deleteImages(String productId, List<String> imageIds) {
        Product product = findProduct(productId);

        if (product.getProductImages() == null || product.getProductImages().isEmpty()) {
            throw new ResourceNotFoundException("Product image", "id", imageIds);
        }

        if (imageIds == null || imageIds.isEmpty()) {
            throw new AppException("At least one image ID is required", HttpStatus.BAD_REQUEST);
        }
        if (imageIds.size() > MAX_PRODUCT_IMAGES) {
            throw new AppException("At most 6 image IDs can be deleted at once", HttpStatus.BAD_REQUEST);
        }

        Set<String> requestedIds = new HashSet<>(imageIds);
        if (requestedIds.size() != imageIds.size()) {
            throw new AppException("Duplicate image IDs are not allowed", HttpStatus.BAD_REQUEST);
        }

        Map<String, ProductImage> imagesById = product.getProductImages().stream()
                .collect(Collectors.toMap(ProductImage::getId, Function.identity()));
        List<ProductImage> imagesToDelete = new ArrayList<>();
        for (String requestedId : imageIds) {
            ProductImage image = imagesById.get(requestedId);
            if (image == null) {
                throw new ResourceNotFoundException("Product image", "id", requestedId);
            }
            imagesToDelete.add(image);
        }

        product.getProductImages().removeAll(imagesToDelete);
        for (int index = 0; index < product.getProductImages().size(); index++) {
            product.getProductImages().get(index).setDisplayOrder(index);
        }
        product.setUpdatedAt(LocalDateTime.now());

        Product saved = saveAndInvalidatePublicCatalog(product);

        // Delete from GCS only after MongoDB no longer references these images.
        for (ProductImage image : imagesToDelete) {
            imageStorage.deleteImageVariants(
                    product.getSellerId(),
                    product.getId(),
                    image);
        }

        return saved;
    }

    private Product saveAndInvalidatePublicCatalog(Product product) {
        Product saved = products.save(product);
        redisCache.invalidateRegion("public-products");
        return saved;
    }

    private Product findProduct(String id) {
        return products.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));
    }
}
