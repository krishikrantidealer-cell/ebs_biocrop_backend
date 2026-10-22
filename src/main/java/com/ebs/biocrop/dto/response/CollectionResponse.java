package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.ProductCollection;

import java.time.Instant;
import java.util.List;
import java.util.function.ToLongFunction;

public record CollectionResponse(String id, String name, String slug, String description,
                                 Boolean isActive, Integer priority, List<SubCollectionResponse> subCollections,
                                 int subCollectionCount,
                                 Instant createdAt, Instant updatedAt, Long productCount, String bannerTitle) {
    public static CollectionResponse from(ProductCollection collection, long productCount, boolean publicView,
                                          ToLongFunction<ProductCollection.SubCollection> subCount) {
        List<SubCollectionResponse> subs = collection.getSubCollections() == null ? List.of()
                : collection.getSubCollections().stream()
                .filter(sub -> !publicView || !Boolean.FALSE.equals(sub.getIsActive()))
                .map(sub -> new SubCollectionResponse(sub.getId() == null ? null : sub.getId().toHexString(),
                        sub.getName(), sub.getSlug(), sub.getIsActive(),
                        sub.getImage(), subCount.applyAsLong(sub)))
                .toList();
        return new CollectionResponse(collection.getId(), collection.getName(), collection.getSlug(),
                collection.getDescription(), collection.getIsActive(), collection.getPriority(), subs, subs.size(),
                collection.getCreatedAt(), collection.getUpdatedAt(), productCount, collection.getBannerTitle());
    }

    public record SubCollectionResponse(String id, String name, String slug, Boolean isActive,
                                        String image, Long productCount) { }
}
