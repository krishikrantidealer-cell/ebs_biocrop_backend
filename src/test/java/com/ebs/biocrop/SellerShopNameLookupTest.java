package com.ebs.biocrop;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.impl.SellerShopNameLookupImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerShopNameLookupTest {
    @Test
    void resolvesCurrentShopNamesInOneBatchAndIgnoresNonSellers() {
        UserRepository users = mock(UserRepository.class);
        User seller = new User("9876543210", UserRole.ROLE_SELLER);
        seller.setEmail("seller@example.com");
        seller.setId("seller-id");
        seller.setShopName("Current Shop Name");
        User customer = new User("9876543211", UserRole.ROLE_CUSTOMER);
        customer.setId("customer-id");
        customer.setShopName("Not a Seller Shop");
        when(users.findAllById(List.of("seller-id", "customer-id"))).thenReturn(List.of(seller, customer));

        Map<String, String> names = new SellerShopNameLookupImpl(users)
                .findCurrentShopNames(List.of("seller-id", "customer-id", "seller-id"));

        assertEquals(Map.of("seller-id", "Current Shop Name"), names);
        verify(users).findAllById(List.of("seller-id", "customer-id"));
    }
}
