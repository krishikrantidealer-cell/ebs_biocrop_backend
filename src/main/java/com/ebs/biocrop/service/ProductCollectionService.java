package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.request.CollectionWriteRequest;
import com.ebs.biocrop.dto.response.CollectionResponse;

import java.util.List;

public interface ProductCollectionService {
    List<CollectionResponse> listPublic();
    CollectionResponse getPublicBySlug(String slug);
    CollectionResponse create(CollectionWriteRequest request);
    CollectionResponse update(String id, CollectionWriteRequest request);
    CollectionResponse deactivate(String id);
}
