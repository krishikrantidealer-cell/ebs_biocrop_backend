package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.Cart;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface CartRepository extends MongoRepository<Cart, String> {

    Optional<Cart> findByUser(String user);
    List<Cart> findByItemsProductId(String productId);
    List<Cart> findByItemsVariantId(String variantId);

    void deleteByUser(String user);
}
