package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.UserProfileUpdateRequest;
import com.ebs.biocrop.entity.Address;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceProfileTest {
    @Mock private UserRepository userRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private RedisJsonCache redisCache;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(userRepository, mongoTemplate, redisCache);
    }

    @Test
    void sellerCanCompleteProfileAndShopNameSyncsToCatalog() {
        User seller = User.newSeller("9876543210", "seller@example.com");
        seller.setId("seller-id");
        when(userRepository.findByPhoneNumber("9876543210")).thenReturn(Optional.of(seller));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserProfileUpdateRequest request = new UserProfileUpdateRequest(
                "Ramesh", "Patel", "Krishi Seva Kendra", completeAddress());

        var response = service.updateProfile("9876543210", request);

        assertEquals("Krishi Seva Kendra", response.getShopName());
        assertTrue(response.getIsProfileComplete());
        verify(mongoTemplate).updateMulti(any(), any(), eq("products"));
        verify(redisCache).invalidateRegion("public-products");
        verify(redisCache).invalidateRegion("public-collections");
    }

    @Test
    void sellerCannotCompleteProfileWithoutShopName() {
        User seller = User.newSeller("9876543210", "seller@example.com");
        when(userRepository.findByPhoneNumber("9876543210")).thenReturn(Optional.of(seller));
        UserProfileUpdateRequest request = new UserProfileUpdateRequest(
                "Ramesh", "Patel", completeAddress());

        AppException error = assertThrows(AppException.class,
                () -> service.updateProfile("9876543210", request));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void customerCannotSetSellerOnlyShopName() {
        User customer = User.newCustomer("9876543210");
        when(userRepository.findByPhoneNumber("9876543210")).thenReturn(Optional.of(customer));
        UserProfileUpdateRequest request = new UserProfileUpdateRequest(
                "Asha", "Patel", "Unapproved Shop", completeAddress());

        AppException error = assertThrows(AppException.class,
                () -> service.updateProfile("9876543210", request));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(userRepository, never()).save(any(User.class));
    }

    private Address completeAddress() {
        Address address = new Address();
        address.setVillageArea("Main Road");
        address.setAddress2("Near Market");
        address.setCityTehsil("Anand");
        address.setState("Gujarat");
        address.setPincode("388001");
        return address;
    }
}
