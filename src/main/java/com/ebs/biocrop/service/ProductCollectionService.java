package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.request.CollectionWriteRequest;
import com.ebs.biocrop.dto.request.CollectionPatchRequest;
import com.ebs.biocrop.dto.response.CollectionResponse;
import org.springframework.data.domain.Page;

import java.util.List;

public interface ProductCollectionService {
    List<CollectionResponse> listPublic();
    CollectionResponse getPublicBySlug(String slug);
    Page<CollectionResponse> listAdmin(Boolean active, int page, int size);
    CollectionResponse getAdminById(String id);
    CollectionResponse create(CollectionWriteRequest request);
    CollectionResponse update(String id, CollectionPatchRequest request);
    CollectionResponse replace(String id, CollectionWriteRequest request);
    List<String> permanentlyDeleteMany(List<String> collectionIds);
    default void permanentlyDelete(String id) {
        permanentlyDeleteMany(List.of(id));
    }
    List<String> deleteSubCollections(String collectionId, List<String> subCollectionIds);
}
