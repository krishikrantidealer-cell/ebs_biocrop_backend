package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductCollection;
import com.ebs.biocrop.dto.request.ProductCollectionAssignmentRequest;
import com.ebs.biocrop.entity.ProductDimensions;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.ProductCollectionRepository;
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
import org.bson.types.ObjectId;
import com.ebs.biocrop.entity.ProductImage;


@Service
public class AdminProductServiceImpl implements AdminProductService {
    private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final int MAX_PRODUCT_IMAGES = 6;
    private final ProductRepository products;
    private final ProductCollectionRepository collections;
    private final CategoryRepository categories;
    private final GcsImageStorageService imageStorage;
    private final ProductImageVariantService imageVariants;
    private final RedisJsonCache redisCache;

    public AdminProductServiceImpl(
            ProductRepository products,
            ProductCollectionRepository collections,
            CategoryRepository categories,
            GcsImageStorageService imageStorage,
            ProductImageVariantService imageVariants,
            RedisJsonCache redisCache) {
        this.products = products;
        this.collections = collections;
        this.categories = categories;
        this.imageStorage = imageStorage;
        this.imageVariants = imageVariants;
        this.redisCache = redisCache;
    }

    @Override
    public Page<Product> listForReview(String status, int page, int size) {
        String normalized = status == null || status.isBlank() ? "INACTIVE" : status.trim().toUpperCase(Locale.ROOT);
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
            validatePublishReady(product);
        }
        product.setStatus(approved ? "ACTIVE" : "INACTIVE");
        product.setIsAvailable(approved);
        product.setUpdatedAt(LocalDateTime.now());
        return saveAndInvalidatePublicCatalog(product);
    }

    private void validatePublishReady(Product product) {
        if (product.getVariants() == null || product.getVariants().isEmpty()) {
            throw new AppException("At least one complete sellable variant is required before approval", HttpStatus.CONFLICT);
        }
        for (ProductVariant variant : product.getVariants()) {
            if (variant == null || variant.getLabel() == null || variant.getLabel().isBlank()
                    || "default title".equalsIgnoreCase(variant.getLabel().trim())) {
                throw new AppException("Every variant needs a specific package label before approval", HttpStatus.CONFLICT);
            }
            if (variant.getPackSize() == null || variant.getPackSize() <= 0
                    || variant.getPackSizeUnit() == null || variant.getPackSizeUnit().isBlank()
                    || variant.getPackQuantity() == null || variant.getPackQuantity() <= 0) {
                throw new AppException("Every variant needs a complete package size and quantity before approval", HttpStatus.CONFLICT);
            }
            if (variant.getDisplayRate() == null || !Double.isFinite(variant.getDisplayRate()) || variant.getDisplayRate() <= 0) {
                throw new AppException("Every variant needs a positive selling price before approval", HttpStatus.CONFLICT);
            }
            if (variant.getStock() == null || variant.getStock() < 0) {
                throw new AppException("Every variant needs a confirmed stock quantity before approval", HttpStatus.CONFLICT);
            }
            ProductDimensions dimensions = variant.getDimensions() == null ? product.getDimensions() : variant.getDimensions();
            if (dimensions == null || !"cm".equalsIgnoreCase(dimensions.getUnit())
                    || !positive(dimensions.getLength()) || !positive(dimensions.getWidth()) || !positive(dimensions.getHeight())) {
                throw new AppException("Every variant needs positive package dimensions in centimeters before approval", HttpStatus.CONFLICT);
            }
        }
    }

    private boolean positive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
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
    public Product assignCollections(String productId, ProductCollectionAssignmentRequest request) {
        Product product = findProduct(productId);
        Set<String> collectionIds = normalizeObjectIds(request.collectionIds(), "collection");
        Set<String> subCollectionIds = normalizeObjectIds(request.subCollectionIds(), "subcollection");
        List<String> assigned = new ArrayList<>();
        List<String> assignedSubcollections = new ArrayList<>();
        for (String collectionId : collectionIds) {
            ProductCollection collection = collections.findById(collectionId)
                    .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                    .orElseThrow(() -> new AppException(
                            "Every selected collection must exist and be active", HttpStatus.BAD_REQUEST));
            assigned.add(collectionId);
            if (collection.getSubCollections() == null) continue;
            for (String subCollectionId : subCollectionIds) {
                ProductCollection.SubCollection sub = collection.getSubCollections().stream()
                        .filter(value -> value.getId() != null
                                && subCollectionId.equals(value.getId().toHexString())
                                && Boolean.TRUE.equals(value.getIsActive()))
                        .findFirst().orElse(null);
                if (sub != null) assignedSubcollections.add(subCollectionId);
            }
        }
        if (assignedSubcollections.size() != subCollectionIds.size()) {
            throw new AppException(
                    "Every selected subcollection must belong to one of the selected active collections",
                    HttpStatus.BAD_REQUEST);
        }
        assigned.addAll(assignedSubcollections);
        product.setAssignedCollections(assigned.stream().distinct().toList());
        product.setUpdatedAt(LocalDateTime.now());
        return saveAndInvalidatePublicCatalog(product);
    }

    private Set<String> normalizeObjectIds(List<String> ids, String label) {
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String id : ids) {
            if (!ObjectId.isValid(id)) {
                throw new AppException("Each " + label + " ID must be a valid MongoDB ObjectId", HttpStatus.BAD_REQUEST);
            }
            if (!normalized.add(new ObjectId(id).toHexString())) {
                throw new AppException("Duplicate " + label + " IDs are not allowed", HttpStatus.BAD_REQUEST);
            }
        }
        return normalized;
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
        redisCache.invalidateRegion("public-collections");
        return saved;
    }

    private Product findProduct(String id) {
        return products.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));
    }
}
