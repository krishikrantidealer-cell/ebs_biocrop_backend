package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends MongoRepository<Review, String> {

    Optional<Review> findByProductIdAndCustomerIdAndIsDeletedFalse(String productId, String customerId);

    Optional<Review> findByIdAndIsDeletedFalse(String id);

    Page<Review> findByProductIdAndStatusAndIsDeletedFalse(String productId, String status, Pageable pageable);

    Page<Review> findByProductIdAndStatusAndRatingAndIsDeletedFalse(String productId, String status, Integer rating, Pageable pageable);

    @Query("{ 'productId': ?0, 'status': ?1, 'isDeleted': false, $or: [ { 'images.0': { $exists: true } }, { 'video': { $ne: null, $ne: '' } } ] }")
    Page<Review> findByProductIdWithMedia(String productId, String status, Pageable pageable);

    List<Review> findByProductIdAndStatusAndIsDeletedFalse(String productId, String status);

    long countByProductIdAndStatusAndIsDeletedFalse(String productId, String status);

    long countByProductIdAndStatusAndRatingAndIsDeletedFalse(String productId, String status, Integer rating);

    // Seller queries
    Page<Review> findBySellerIdAndIsDeletedFalse(String sellerId, Pageable pageable);

    List<Review> findBySellerIdAndIsDeletedFalse(String sellerId);

    Page<Review> findBySellerIdAndRatingAndIsDeletedFalse(String sellerId, Integer rating, Pageable pageable);

    Page<Review> findBySellerIdAndProductIdAndIsDeletedFalse(String sellerId, String productId, Pageable pageable);

    Page<Review> findBySellerIdAndProductIdAndRatingAndIsDeletedFalse(String sellerId, String productId, Integer rating, Pageable pageable);

    // Admin queries
    Page<Review> findByIsDeletedFalse(Pageable pageable);

    Page<Review> findByProductIdAndIsDeletedFalse(String productId, Pageable pageable);

    Page<Review> findByRatingAndIsDeletedFalse(Integer rating, Pageable pageable);

    Page<Review> findByProductIdAndRatingAndIsDeletedFalse(String productId, Integer rating, Pageable pageable);
}
