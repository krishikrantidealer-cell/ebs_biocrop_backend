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

        String expectedPrefix =
                "Sellers/" + sellerId + "/products/" + productId + "/";

        for (String objectName : List.of(
                image.getOriginalObjectName(),
                image.getMidObjectName(),
                image.getLowObjectName())) {

            if (objectName != null && objectName.startsWith(expectedPrefix)) {
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

        String expectedPrefix =
                "Sellers/" + sellerId + "/products/" + productId + "/";

        if (objectName == null || !objectName.startsWith(expectedPrefix)) {
            throw badRequest("Image object does not belong to this product");
        }

        URL signedUrl = storage.signUrl(
                BlobInfo.newBuilder(bucketName, objectName).build(),
                15,
                TimeUnit.MINUTES,
                Storage.SignUrlOption.withV4Signature());

        return signedUrl.toString();
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