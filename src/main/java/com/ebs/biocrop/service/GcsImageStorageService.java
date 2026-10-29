package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.ProductImage;
import com.ebs.biocrop.exception.AppException;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.net.URL;
import java.util.concurrent.TimeUnit;

@Service
public class GcsImageStorageService {

    private final Storage storage;
    private final String bucketName;

    public GcsImageStorageService(
            Storage storage,
            @Value("${app.gcs.bucket-name}") String bucketName) {
        this.storage = storage;
        this.bucketName = bucketName;
    }

    public String uploadVariant(
            String sellerId,
            String productId,
            String imageId,
            ImageResolution resolution,
            String extension,
            String contentType,
            byte[] imageBytes) {

        requireSafeSegment(sellerId, "Seller ID");
        requireSafeSegment(productId, "Product ID");
        requireSafeSegment(imageId, "Image ID");

        if (resolution == null) {
            throw badRequest("Image resolution is required");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw badRequest("Image data is empty");
        }

        String normalizedExtension =
                extension == null ? "" : extension.toLowerCase(Locale.ROOT);

        boolean validFormat =
                ("jpg".equals(normalizedExtension) && "image/jpeg".equals(contentType))
                        || ("png".equals(normalizedExtension) && "image/png".equals(contentType));

        if (!validFormat) {
            throw badRequest("Only JPEG and PNG image variants are supported");
        }

        String objectName = "Sellers/" + sellerId
                + "/products/" + productId
                + "/" + resolution.folder()
                + "/" + imageId + "." + normalizedExtension;

        BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, objectName)
                .setContentType(contentType)
                .build();

        storage.create(
                blobInfo,
                imageBytes,
                Storage.BlobTargetOption.doesNotExist());

        return objectName;
    }

    public String uploadCategoryImage(String categoryId, byte[] imageBytes, String extension, String contentType) {
        requireSafeSegment(categoryId, "Category ID");
        if (imageBytes == null || imageBytes.length == 0) {
            throw badRequest("Image data is empty");
        }
        String normalizedExtension = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        boolean validFormat = ("jpg".equals(normalizedExtension) && "image/jpeg".equals(contentType))
                || ("png".equals(normalizedExtension) && "image/png".equals(contentType));
        if (!validFormat) {
            throw badRequest("Only JPEG and PNG category images are supported");
        }
        String objectName = "Categories/" + categoryId + "/images/" + UUID.randomUUID() + "." + normalizedExtension;
        BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, objectName).setContentType(contentType).build();
        storage.create(blobInfo, imageBytes, Storage.BlobTargetOption.doesNotExist());
        return "gs://" + bucketName + "/" + objectName;
    }

    public String createCategoryImageReadUrl(String categoryId, String storageUri) {
        requireSafeSegment(categoryId, "Category ID");
        String objectName = categoryImageObjectName(categoryId, storageUri);
        return storage.signUrl(BlobInfo.newBuilder(bucketName, objectName).build(), 15, TimeUnit.MINUTES,
                Storage.SignUrlOption.withV4Signature()).toString();
    }

    public void deleteCategoryImage(String categoryId, String storageUri) {
        requireSafeSegment(categoryId, "Category ID");
        if (storageUri == null) return;
        storage.delete(bucketName, categoryImageObjectName(categoryId, storageUri));
    }

    private String categoryImageObjectName(String categoryId, String storageUri) {
        if (storageUri == null) {
            throw badRequest("Category image storage URI is required");
        }
        String objectName = storageUri;
        String expectedGsPrefix = "gs://" + bucketName + "/";
        if (storageUri.startsWith("gs://")) {
            if (!storageUri.startsWith(expectedGsPrefix)) {
                throw badRequest("Category image belongs to a different storage bucket");
            }
            objectName = storageUri.substring(expectedGsPrefix.length());
        }
        String categoryPrefix = "Categories/" + categoryId + "/";
        boolean validCategoryImagePath = objectName.startsWith(categoryPrefix + "images/")
                || objectName.startsWith(categoryPrefix + "icons/");
        if (!validCategoryImagePath) {
            throw badRequest("Image object does not belong to this category");
        }
        return objectName;
    }

    public ProductImage uploadImage(
            String sellerId,
            String productId,
            ProductImageVariantService.Variants variants,
            int displayOrder) {

        String imageId = UUID.randomUUID().toString();
        List<String> uploadedObjectNames = new ArrayList<>();

        try {
            String original = uploadVariant(
                    sellerId, productId, imageId, ImageResolution.ORIGINAL,
                    variants.extension(), variants.contentType(), variants.originalBytes());
            uploadedObjectNames.add(original);

            String mid = uploadVariant(
                    sellerId, productId, imageId, ImageResolution.MID,
                    variants.extension(), variants.contentType(), variants.midBytes());
            uploadedObjectNames.add(mid);

            String low = uploadVariant(
                    sellerId, productId, imageId, ImageResolution.LOW,
                    variants.extension(), variants.contentType(), variants.lowBytes());
            uploadedObjectNames.add(low);

            ProductImage image = new ProductImage();
            image.setId(imageId);
            image.setOriginalObjectName(original);
            image.setMidObjectName(mid);
            image.setLowObjectName(low);
            image.setDisplayOrder(displayOrder);
            return image;
        } catch (RuntimeException uploadFailure) {
            for (String objectName : uploadedObjectNames) {
                try {
                    storage.delete(bucketName, objectName);
                } catch (RuntimeException cleanupFailure) {
                    uploadFailure.addSuppressed(cleanupFailure);
                }
            }
            throw uploadFailure;
        }
    }


    public void deleteImageVariants(
            String sellerId,
            String productId,
            ProductImage image) {

        requireSafeSegment(sellerId, "Seller ID");
        requireSafeSegment(productId, "Product ID");

        if (image == null) {
            return;
        }

        for (String objectName : List.of(
                image.getOriginalObjectName(),
                image.getMidObjectName(),
                image.getLowObjectName())) {

            if (belongsToProduct(productId, objectName)) {
                storage.delete(bucketName, objectName);
            }
        }
    }

    public String createReadUrl(
            String sellerId,
            String productId,
            String objectName) {

        requireSafeSegment(sellerId, "Seller ID");
        requireSafeSegment(productId, "Product ID");

        if (!belongsToProduct(productId, objectName)) {
            throw badRequest("Image object does not belong to this product");
        }

        URL signedUrl = storage.signUrl(
                BlobInfo.newBuilder(bucketName, objectName).build(),
                15,
                TimeUnit.MINUTES,
                Storage.SignUrlOption.withV4Signature());

        return signedUrl.toString();
    }

    /**
     * Image object names are stable asset identifiers. A product may be reassigned to another seller
     * without moving its existing GCS objects, so validate the product folder rather than the
     * current seller folder. This still prevents one product from signing or deleting another
     * product's objects.
     */
    static boolean belongsToProduct(String productId, String objectName) {
        if (objectName == null) {
            return false;
        }

        String[] segments = objectName.split("/", 6);
        return segments.length == 6
                && "Sellers".equals(segments[0])
                && segments[1].matches("[A-Za-z0-9_-]{1,128}")
                && "products".equals(segments[2])
                && productId.equals(segments[3])
                && List.of("original", "mid", "low").contains(segments[4])
                && !segments[5].isBlank();
    }

    private void requireSafeSegment(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw badRequest(label + " is invalid");
        }
    }

    private AppException badRequest(String message) {
        return new AppException(message, HttpStatus.BAD_REQUEST);
    }
}
