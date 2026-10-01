package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByPhoneNumber(String phoneNumber);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findByPasswordResetTokenHash(String passwordResetTokenHash);

    boolean existsByPhoneNumber(String phoneNumber);

    @Query(value = "{ 'role': ?0, '$or': [ { 'isDeleted': false }, { 'isDeleted': null } ] }", count = true)
    long countActiveByRole(UserRole role);
}
