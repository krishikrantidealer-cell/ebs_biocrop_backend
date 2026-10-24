package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.dto.response.AdminUserResponse;
import com.ebs.biocrop.entity.Cart;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.Wishlist;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.AdminUserService;
import com.ebs.biocrop.service.RedisJsonCache;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AdminUserServiceImpl implements AdminUserService {
    private static final int MAX_SEARCH_LENGTH = 100;

    private final MongoTemplate mongoTemplate;
    private final UserRepository users;
    private final RedisJsonCache redisCache;

    public AdminUserServiceImpl(
            MongoTemplate mongoTemplate,
            UserRepository users,
            RedisJsonCache redisCache) {
        this.mongoTemplate = mongoTemplate;
        this.users = users;
        this.redisCache = redisCache;
    }

    @Override
    public Page<AdminUserResponse> list(
            String role,
            String query,
            Boolean blocked,
            Boolean deleted,
            int page,
            int size) {
        List<Criteria> filters = new ArrayList<>();
        addRoleFilter(filters, role);
        addBooleanFilter(filters, "isBlocked", blocked);
        addBooleanFilter(filters, "isDeleted", deleted);
        addSearchFilter(filters, query);

        Pageable pageable = PageRequestSupport.create(
                page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Criteria criteria = filters.isEmpty()
                ? new Criteria()
                : new Criteria().andOperator(filters.toArray(Criteria[]::new));
        Query pageQuery = new Query(criteria).with(pageable);

        List<AdminUserResponse> content = mongoTemplate.find(pageQuery, User.class)
                .stream()
                .map(AdminUserResponse::from)
                .toList();
        long total = mongoTemplate.count(Query.of(pageQuery).limit(-1).skip(-1), User.class);
        return new PageImpl<>(content, pageable, total);
    }

    @Override
    public AdminUserResponse getById(String id) {
        return AdminUserResponse.from(findUser(id));
    }

    @Override
    public AdminUserResponse setBlockedStatus(String id, boolean blocked) {
        User user = findUser(id);
        boolean isTransitionToBlocked = blocked && !Boolean.TRUE.equals(user.getIsBlocked());
        if (isTransitionToBlocked
                && user.getRole() == UserRole.ROLE_ADMIN
                && users.countActiveByRole(UserRole.ROLE_ADMIN) <= 1) {
            throw new AppException("The last active admin account cannot be blocked", HttpStatus.CONFLICT);
        }
        if (isTransitionToBlocked && user.getRole() == UserRole.ROLE_SELLER) {
            deactivateSellerListings(id);
        }

        user.setIsBlocked(blocked);
        user.setUpdatedAt(LocalDateTime.now());
        return AdminUserResponse.from(users.save(user));
    }

    @Override
    public AdminUserResponse setDeletedStatus(String id, boolean deleted) {
        User user = findUser(id);
        boolean isTransitionToDeleted = deleted && !Boolean.TRUE.equals(user.getIsDeleted());
        if (isTransitionToDeleted
                && user.getRole() == UserRole.ROLE_ADMIN
                && users.countActiveByRole(UserRole.ROLE_ADMIN) <= 1) {
            throw new AppException("The last active admin account cannot be deleted", HttpStatus.CONFLICT);
        }
        if (isTransitionToDeleted && user.getRole() == UserRole.ROLE_SELLER) {
            deactivateSellerListings(id);
        }

        user.setIsDeleted(deleted);
        user.setUpdatedAt(LocalDateTime.now());
        return AdminUserResponse.from(users.save(user));
    }

    @Override
    public UserRole assignRole(String id, String requestedRole) {
        User user = findUser(id);
        UserRole role = parseRole(requestedRole);

        if (Boolean.TRUE.equals(user.getIsDeleted())) {
            throw new AppException("A deleted account cannot be assigned a role", HttpStatus.CONFLICT);
        }
        if (user.getRole() == UserRole.ROLE_ADMIN
                && role != UserRole.ROLE_ADMIN
                && users.countActiveByRole(UserRole.ROLE_ADMIN) <= 1) {
            throw new AppException("The last active admin account cannot be demoted", HttpStatus.CONFLICT);
        }
        if (user.getRole() == UserRole.ROLE_SELLER && role != UserRole.ROLE_SELLER) {
            deactivateSellerListings(id);
        }

        user.setRole(role);
        user.setUpdatedAt(LocalDateTime.now());
        users.save(user);
        return role;
    }

    @Override
    @Transactional
    public void permanentlyDelete(String adminId, String id) {
        User user = findUser(id);

        if (adminId != null && adminId.equals(id)) {
            throw new AppException("You cannot delete your own admin account", HttpStatus.CONFLICT);
        }
        if (user.getRole() == UserRole.ROLE_ADMIN
                && users.countActiveByRole(UserRole.ROLE_ADMIN) <= 1) {
            throw new AppException("The last active admin account cannot be permanently deleted", HttpStatus.CONFLICT);
        }
        if (!Boolean.TRUE.equals(user.getIsDeleted())) {
            throw new AppException("Deactivate the user account before permanently deleting it", HttpStatus.CONFLICT);
        }

        boolean hasOrders = mongoTemplate.exists(
                Query.query(new Criteria().orOperator(
                        Criteria.where("customerId").is(id),
                        Criteria.where("sellerId").is(id))),
                Order.class);
        if (hasOrders) {
            throw new AppException("Users with associated order records cannot be permanently deleted", HttpStatus.CONFLICT);
        }

        boolean hasProducts = mongoTemplate.exists(
                Query.query(Criteria.where("sellerId").is(id)),
                Product.class);
        if (hasProducts) {
            throw new AppException("Sellers with existing product listings cannot be permanently deleted", HttpStatus.CONFLICT);
        }

        mongoTemplate.remove(Query.query(Criteria.where("user").is(id)), Cart.class);
        mongoTemplate.remove(Query.query(Criteria.where("user").is(id)), Wishlist.class);

        users.delete(user);
    }

    private void addRoleFilter(List<Criteria> filters, String role) {
        if (role == null || role.isBlank() || "ALL".equalsIgnoreCase(role)) {
            return;
        }

        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("CUSTOMERS")) {
            normalized = "CUSTOMER";
        } else if (normalized.equals("SELLERS")) {
            normalized = "SELLER";
        }

        UserRole parsed;
        try {
            String enumName = normalized.startsWith("ROLE_") ? normalized : "ROLE_" + normalized;
            parsed = UserRole.valueOf(enumName);
        } catch (IllegalArgumentException invalidRole) {
            throw new AppException("Role must be CUSTOMER, SELLER, ADMIN, or ALL", HttpStatus.BAD_REQUEST);
        }
        filters.add(Criteria.where("role").is(parsed));
    }

    private void addBooleanFilter(List<Criteria> filters, String field, Boolean value) {
        if (value == null) {
            return;
        }
        if (value) {
            filters.add(Criteria.where(field).is(true));
            return;
        }
        filters.add(new Criteria().orOperator(
                Criteria.where(field).is(false),
                Criteria.where(field).exists(false),
                Criteria.where(field).is(null)));
    }

    private void addSearchFilter(List<Criteria> filters, String query) {
        if (query == null || query.isBlank()) {
            return;
        }
        String normalized = query.trim();
        if (normalized.length() > MAX_SEARCH_LENGTH) {
            throw new AppException("Search query must be at most 100 characters", HttpStatus.BAD_REQUEST);
        }

        String prefix = "^" + java.util.regex.Pattern.quote(normalized);
        filters.add(new Criteria().orOperator(
                Criteria.where("firstName").regex(prefix, "i"),
                Criteria.where("lastName").regex(prefix, "i"),
                Criteria.where("phoneNumber").regex(prefix, "i"),
                Criteria.where("email").regex(prefix, "i"),
                Criteria.where("shopName").regex(prefix, "i")));
    }

    private UserRole parseRole(String requestedRole) {
        try {
            return UserRole.valueOf(requestedRole.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalidRole) {
            throw new AppException(
                    "Role must be ROLE_CUSTOMER, ROLE_SELLER, or ROLE_ADMIN",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private void deactivateSellerListings(String sellerId) {
        mongoTemplate.updateMulti(
                Query.query(Criteria.where("sellerId").is(sellerId)),
                new Update()
                        .set("status", "INACTIVE")
                        .set("isAvailable", false)
                        .set("isFeatured", false)
                        .set("updatedAt", LocalDateTime.now()),
                "products");
        redisCache.invalidateRegion("public-products");
        redisCache.invalidateRegion("public-collections");
    }

    private User findUser(String id) {
        return users.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }
}
