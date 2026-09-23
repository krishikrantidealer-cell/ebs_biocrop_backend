package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.Cart;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CartRepository extends MongoRepository<Cart, String> {

    Optional<Cart> findByPhoneNumber(String phoneNumber);

    Optional<Cart> findByUserId(String userId);

    void deleteByPhoneNumber(String phoneNumber);
}
