package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.websocket.WebSocketService;
import java.util.Map;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.dto.response.AdminUserResponse;
import com.ebs.biocrop.dto.response.AdminSellerDashboardResponse;
import com.ebs.biocrop.dto.request.AdminManagedUserCreateRequest;
import com.ebs.biocrop.dto.request.AdminManagedUserUpdateRequest;
import com.ebs.biocrop.entity.Cart;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.Review;
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
import java.util.Objects;

@Service
public class AdminUserServiceImpl implements AdminUserService {
    private static final int MAX_SEARCH_LENGTH = 100;

    private final MongoTemplate mongoTemplate;
    private final UserRepository users;
    private final RedisJsonCache redisCache;
    private final WebSocketService webSocketService;

    public AdminUserServiceImpl(
            MongoTemplate mongoTemplate,
            UserRepository users,
            RedisJsonCache redisCache,
            WebSocketService webSocketService) {
        this.mongoTemplate = mongoTemplate;
        this.users = users;
        this.redisCache = redisCache;
        this.webSocketService = webSocketService;
    }

    @Override
    public AdminUserResponse createManagedUser(String requestedRole, AdminManagedUserCreateRequest request) {
        UserRole role;
        try {
            role = UserRole.valueOf("ROLE_" + requestedRole.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalidRole) {
            throw new AppException("Only CUSTOMER or SELLER accounts can be created here", HttpStatus.BAD_REQUEST);
        }
        if (role != UserRole.ROLE_CUSTOMER && role != UserRole.ROLE_SELLER) {
            throw new AppException("Only CUSTOMER or SELLER accounts can be created here", HttpStatus.BAD_REQUEST);
        }
        String phone = request.getPhoneNumber();
        if (users.existsByPhoneNumber(phone)) {
            throw new AppException("A user with this phone number already exists", HttpStatus.CONFLICT);
        }
        String email = normalizeEmail(request.getEmail());
        if (email != null && users.findByEmailIgnoreCase(email).isPresent()) {
            throw new AppException("A user with this email already exists", HttpStatus.CONFLICT);
        }
        if (role == UserRole.ROLE_SELLER && request.getMonthlyTarget() != null && request.getMonthlyTarget() < 0) {
            throw new AppException("Monthly target cannot be negative", HttpStatus.BAD_REQUEST);
        }

        User user = role == UserRole.ROLE_SELLER ? User.newSeller(phone, email) : User.newCustomer(phone);
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setAddress(request.getAddress());
        user.setStatus(request.getStatus() == null ? "ACTIVE" : request.getStatus());
        user.setNotes(request.getNotes());
        if (role == UserRole.ROLE_SELLER) {
            user.setShopName(request.getShopName());
            user.setGstNumber(request.getGstNumber());
            user.setLicenceImage(request.getLicenceImage());
            user.setShopImage(request.getShopImage());
            user.setMonthlyTarget(request.getMonthlyTarget());
            user.setPreferredLanguage(request.getPreferredLanguage());
        }
        user.setIsProfileComplete(user.hasCompleteProfile());
        user.setUpdatedAt(LocalDateTime.now());
        try {
            return AdminUserResponse.from(users.save(user));
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new AppException("A user with this phone number or email already exists", HttpStatus.CONFLICT);
        }
    }

    @Override
    @Transactional
    public AdminUserResponse updateManagedUser(String id, AdminManagedUserUpdateRequest request) {
        User user = findUser(id);
        if (user.getRole() != UserRole.ROLE_CUSTOMER && user.getRole() != UserRole.ROLE_SELLER) {
            throw new AppException("Only customer and seller profiles can be updated here", HttpStatus.CONFLICT);
        }
        if (request.getEmail() != null) {
            String email = normalizeEmail(request.getEmail());
            users.findByEmailIgnoreCase(email).filter(existing -> !existing.getId().equals(id)).ifPresent(existing -> {
                throw new AppException("A user with this email already exists", HttpStatus.CONFLICT);
            });
            user.setEmail(email);
        }
        if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
        if (request.getLastName() != null) user.setLastName(request.getLastName());
        if (request.getAddress() != null) user.setAddress(request.getAddress());
        if (request.getStatus() != null) user.setStatus(request.getStatus());
        if (request.getNotes() != null) user.setNotes(request.getNotes());
        String priorShopName = user.getShopName();
        if (user.getRole() == UserRole.ROLE_SELLER) {
            if (request.getShopName() != null) user.setShopName(request.getShopName());
            if (request.getGstNumber() != null) user.setGstNumber(request.getGstNumber());
            if (request.getLicenceImage() != null) user.setLicenceImage(request.getLicenceImage());
            if (request.getShopImage() != null) user.setShopImage(request.getShopImage());
            if (request.getMonthlyTarget() != null) {
                if (request.getMonthlyTarget() < 0) throw new AppException("Monthly target cannot be negative", HttpStatus.BAD_REQUEST);
                user.setMonthlyTarget(request.getMonthlyTarget());
            }
            if (request.getPreferredLanguage() != null) user.setPreferredLanguage(request.getPreferredLanguage());
        }
        user.setIsProfileComplete(user.hasCompleteProfile());
        user.setUpdatedAt(LocalDateTime.now());
        try {
            User saved = users.save(user);
            if (saved.getRole() == UserRole.ROLE_SELLER && !Objects.equals(priorShopName, saved.getShopName())) {
                mongoTemplate.updateMulti(
                        Query.query(Criteria.where("sellerId").is(id)),
                        new Update().set("sellerName", saved.getShopName())
                                .set("updatedAt", saved.getUpdatedAt())
                                .inc("version", 1),
                        "products");
                redisCache.invalidateRegion("public-products");
                redisCache.invalidateRegion("public-collections");
            }
            return AdminUserResponse.from(saved);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new AppException("A user with this email already exists", HttpStatus.CONFLICT);
        }
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
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
    public AdminSellerDashboardResponse getSellerDashboard(String id) {
        User seller = findUser(id);
        if (seller.getRole() != UserRole.ROLE_SELLER) {
            throw new AppException("Seller dashboard is only available for seller accounts", HttpStatus.CONFLICT);
        }
        Query bySeller = Query.query(Criteria.where("sellerId").is(id));
        long totalProducts = mongoTemplate.count(bySeller, Product.class);
        Query activeProducts = Query.query(new Criteria().andOperator(
                Criteria.where("sellerId").is(id),
                Criteria.where("status").is("ACTIVE"),
                Criteria.where("isAvailable").is(true),
                Criteria.where("isDeleted").ne(true)));
        long activeProductCount = mongoTemplate.count(activeProducts, Product.class);
        long totalOrders = mongoTemplate.count(bySeller, Order.class);
        long totalReviews = mongoTemplate.count(bySeller, Review.class);
        return new AdminSellerDashboardResponse(
                AdminUserResponse.from(seller), totalProducts, activeProductCount, totalOrders, totalReviews);
    }

    @Override
    public AdminUserResponse setBlockedStatus(String id, boolean blocked) {
        User user = findUser(id);
        ensureCustomerOrSellerLifecycle(user);
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
        User saved = users.save(user);
        if (blocked) {
            webSocketService.sendToUser(id, "auth:session_revoked", Map.of(
                    "userId", id,
                    "reason", "Account has been blocked by administrator"
            ));
        }
        return AdminUserResponse.from(saved);
    }

    @Override
    public AdminUserResponse setDeletedStatus(String id, boolean deleted) {
        User user = findUser(id);
        ensureCustomerOrSellerLifecycle(user);
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
        User saved = users.save(user);
        if (deleted) {
            webSocketService.sendToUser(id, "auth:session_revoked", Map.of(
                    "userId", id,
                    "reason", "Account has been deactivated"
            ));
        }
        return AdminUserResponse.from(saved);
    }

    @Override
    public AdminUserResponse reviewSellerKyc(String id, boolean approved) {
        User seller = findUser(id);
        if (seller.getRole() != UserRole.ROLE_SELLER) {
            throw new AppException("KYC review is only available for seller accounts", HttpStatus.CONFLICT);
        }
        if (approved && !seller.hasRequiredSellerKycInformation()) {
            throw new AppException("Seller must provide GST number, licence image, and shop image before approval",
                    HttpStatus.CONFLICT);
        }

        seller.setKycStatus(approved ? "APPROVED" : null);
        seller.setUpdatedAt(LocalDateTime.now());
        User saved = users.save(seller);
        webSocketService.sendToUser(id, "users:kyc_updated", Map.of(
                "userId", id,
                "status", approved ? "APPROVED" : "PENDING"
        ));
        return AdminUserResponse.from(saved);
    }

    @Override
    public UserRole assignRole(String id, String requestedRole) {
        User user = findUser(id);
        UserRole role = parseRole(requestedRole);
        if (user.getRole() != role) {
            throw new AppException(
                    "Account roles cannot be changed after creation. Create a new account with the required role.",
                    HttpStatus.CONFLICT);
        }
        return user.getRole();
    }

    @Override
    @Transactional
    public void permanentlyDelete(String adminId, String id) {
        User user = findUser(id);

        ensureCustomerOrSellerLifecycle(user);

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
        webSocketService.sendToUser(id, "auth:session_revoked", Map.of(
                "userId", id,
                "reason", "Account has been permanently deleted"
        ));
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

    private void ensureCustomerOrSellerLifecycle(User user) {
        if (user.getRole() != UserRole.ROLE_CUSTOMER && user.getRole() != UserRole.ROLE_SELLER) {
            throw new AppException("Account lifecycle actions are only available for customers and sellers",
                    HttpStatus.CONFLICT);
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
