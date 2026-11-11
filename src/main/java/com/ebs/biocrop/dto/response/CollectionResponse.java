package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.ProductCollection;
import com.ebs.biocrop.service.GcsImageStorageService;

import java.time.Instant;
import java.util.List;
import java.util.function.ToLongFunction;

public record CollectionResponse(String id, String name, String slug, String description,
                                 String image,
                                 Boolean isActive, Integer priority, List<SubCollectionResponse> subCollections,
                                 int subCollectionCount,
                                 Instant createdAt, Instant updatedAt, Long productCount, String bannerTitle,
                                 Integer version) {
    public static CollectionResponse from(ProductCollection collection, long productCount, boolean publicView,
                                          ToLongFunction<ProductCollection.SubCollection> subCount) {
        return from(collection, productCount, publicView, subCount, null);
    }

    public static CollectionResponse from(ProductCollection collection, long productCount, boolean publicView,
                                          ToLongFunction<ProductCollection.SubCollection> subCount,
                                          GcsImageStorageService imageStorage) {
        String colImage = collection.getImage() == null ? null
                : (imageStorage == null ? collection.getImage() : imageStorage.createCollectionImageReadUrl(collection.getId(), collection.getImage()));

        List<SubCollectionResponse> subs = collection.getSubCollections() == null ? List.of()
                : collection.getSubCollections().stream()
                .filter(sub -> !publicView || !Boolean.FALSE.equals(sub.getIsActive()))
                .map(sub -> new SubCollectionResponse(
                        sub.getId() == null ? null : sub.getId().toHexString(),
                        sub.getName(),
                        sub.getSlug(),
                        sub.getIsActive(),
                        sub.getImage() == null ? null : (imageStorage == null ? sub.getImage() : imageStorage.createCollectionImageReadUrl(collection.getId(), sub.getImage())),
                        subCount.applyAsLong(sub)))
                .toList();
        return new CollectionResponse(collection.getId(), collection.getName(), collection.getSlug(),
                collection.getDescription(), colImage, collection.getIsActive(), collection.getPriority(), subs, subs.size(),
                collection.getCreatedAt(), collection.getUpdatedAt(), productCount, collection.getBannerTitle(),
                publicView ? null : collection.getVersion());
    }

    public record SubCollectionResponse(String id, String name, String slug, Boolean isActive,
                                        String image, Long productCount) { }
}
