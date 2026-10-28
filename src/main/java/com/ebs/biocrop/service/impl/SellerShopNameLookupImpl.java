package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.SellerShopNameLookup;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class SellerShopNameLookupImpl implements SellerShopNameLookup {
    private final UserRepository users;

    public SellerShopNameLookupImpl(UserRepository users) {
        this.users = users;
    }

    @Override
    public Map<String, String> findCurrentShopNames(Collection<String> sellerIds) {
        if (sellerIds == null || sellerIds.isEmpty()) return Map.of();
        Iterable<User> foundSellers = users.findAllById(
                sellerIds.stream().filter(Objects::nonNull).distinct().toList());
        return StreamSupport.stream(foundSellers.spliterator(), false)
                .filter(user -> user.getRole() == UserRole.ROLE_SELLER)
                .filter(user -> user.getId() != null && user.getShopName() != null && !user.getShopName().isBlank())
                .collect(Collectors.toMap(User::getId, User::getShopName, (first, ignored) -> first));
    }
}
