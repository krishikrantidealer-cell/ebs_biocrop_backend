package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.websocket.WebSocketService;
import java.util.Map;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.dto.response.AdminUserResponse;
import com.ebs.biocrop.dto.response.AdminSellerDashboardResponse;
import com.ebs.biocrop.dto.response.PermanentDeleteUsersResponse;
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
        if (request.getRole() != null && !role.name().equalsIgnoreCase(request.getRole())) {
            throw new AppException("The role in the request must match the endpoint", HttpStatus.BAD_REQUEST);
        }
        if (role == UserRole.ROLE_SELLER
                && (Boolean.TRUE.equals(request.getIsKycComplete())
                || "APPROVED".equalsIgnoreCase(request.getKycStatus()))) {
            throw new AppException("Seller KYC approval must use the admin KYC review endpoint", HttpStatus.CONFLICT);
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
        if (role == UserRole.ROLE_SELLER
                && ((request.getProductCodeSequence() != null && request.getProductCodeSequence() != 0L)
                || (request.getOrderNumberSequence() != null && request.getOrderNumberSequence() != 0L))) {
            throw new AppException("Seller sequences are server-managed and must start at zero", HttpStatus.BAD_REQUEST);
        }

        User user = role == UserRole.ROLE_SELLER ? User.newSeller(phone, email) : User.newCustomer(phone);
        if (request.getPasswordHash() != null) user.setPasswordHash(request.getPasswordHash());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setAddress(request.getAddress());
        user.setShippingAddresses(request.getShippingAddresses() == null
                ? new ArrayList<>() : new ArrayList<>(request.getShippingAddresses()));
        user.setStatus(request.getStatus() == null ? "ACTIVE" : request.getStatus());
        user.setNotes(request.getNotes());
        if (request.getNotesHistory() != null) user.setNotesHistory(new ArrayList<>(request.getNotesHistory()));
        user.setIsBlocked(Boolean.TRUE.equals(request.getIsBlocked()));
        user.setIsDeleted(Boolean.TRUE.equals(request.getIsDeleted()));
        if (request.getIsVerified() != null) user.setIsVerified(request.getIsVerified());
        if (role == UserRole.ROLE_SELLER) {
            user.setShopName(request.getShopName());
            user.setGstNumber(request.getGstNumber());
            user.setLicenceImage(request.getLicenceImage());
            user.setShopImage(request.getShopImage());
            user.setMonthlyTarget(request.getMonthlyTarget());
            user.setPreferredLanguage(request.getPreferredLanguage());
            if (request.getBankDetails() != null) user.setBankDetails(merge(user.getBankDetails(), request.getBankDetails()));
            if (request.getSettlementTerms() != null) user.setSettlementTerms(merge(user.getSettlementTerms(), request.getSettlementTerms()));
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
        return updateManagedUser(id, request, false);
    }

    @Override
    @Transactional
    public AdminUserResponse replaceManagedUser(String id, AdminManagedUserUpdateRequest request) {
        return updateManagedUser(id, request, true);
    }

    private AdminUserResponse updateManagedUser(String id, AdminManagedUserUpdateRequest request, boolean replace) {
        User user = findUser(id);
        if (user.getRole() != UserRole.ROLE_CUSTOMER && user.getRole() != UserRole.ROLE_SELLER) {
            throw new AppException("Only customer and seller profiles can be updated here", HttpStatus.CONFLICT);
        }
        if (replace && (request.getPhoneNumber() == null || request.getPhoneNumber().isBlank())) {
            throw new AppException("Phone number is required when replacing a user profile", HttpStatus.BAD_REQUEST);
        }
        if ((replace || request.getPhoneNumber() != null) && !Objects.equals(request.getPhoneNumber(), user.getPhoneNumber())) {
            if (request.getPhoneNumber() == null) throw new AppException("Phone number cannot be null", HttpStatus.BAD_REQUEST);
            users.findByPhoneNumber(request.getPhoneNumber()).filter(existing -> !existing.getId().equals(id)).ifPresent(existing -> {
                throw new AppException("A user with this phone number already exists", HttpStatus.CONFLICT);
            });
            user.setPhoneNumber(request.getPhoneNumber());
        }
        if (replace || request.getEmail() != null) {
            String email = normalizeEmail(request.getEmail());
            if (email != null) {
                users.findByEmailIgnoreCase(email).filter(existing -> !existing.getId().equals(id)).ifPresent(existing -> {
                    throw new AppException("A user with this email already exists", HttpStatus.CONFLICT);
                });
            }
            user.setEmail(email);
        }
        if (replace || request.getFirstName() != null) user.setFirstName(request.getFirstName());
        if (replace || request.getLastName() != null) user.setLastName(request.getLastName());
        if (replace || request.getAddress() != null) user.setAddress(request.getAddress());
        if (replace || request.getShippingAddresses() != null) user.setShippingAddresses(
                request.getShippingAddresses() == null ? new ArrayList<>() : new ArrayList<>(request.getShippingAddresses()));
        if (replace || request.getStatus() != null) user.setStatus(request.getStatus() == null ? "ACTIVE" : request.getStatus());
        if (replace || request.getNotes() != null) user.setNotes(request.getNotes());
        if (replace || request.getNotesHistory() != null) user.setNotesHistory(
                request.getNotesHistory() == null ? new ArrayList<>() : new ArrayList<>(request.getNotesHistory()));
        if (replace || request.getIsVerified() != null) user.setIsVerified(Boolean.TRUE.equals(request.getIsVerified()));
        String priorShopName = user.getShopName();
        if (user.getRole() == UserRole.ROLE_SELLER) {
            if (replace || request.getShopName() != null) user.setShopName(request.getShopName());
            if (replace || request.getGstNumber() != null) user.setGstNumber(request.getGstNumber());
            if (replace || request.getLicenceImage() != null) user.setLicenceImage(request.getLicenceImage());
            if (replace || request.getShopImage() != null) user.setShopImage(request.getShopImage());
            if (replace || request.getMonthlyTarget() != null) {
                if (request.getMonthlyTarget() != null && request.getMonthlyTarget() < 0)
                    throw new AppException("Monthly target cannot be negative", HttpStatus.BAD_REQUEST);
                user.setMonthlyTarget(request.getMonthlyTarget());
            }
            if (replace || request.getPreferredLanguage() != null) user.setPreferredLanguage(request.getPreferredLanguage());
            if (replace || request.getBankDetails() != null) user.setBankDetails(request.getBankDetails() == null
                    ? defaultBankDetails() : merge(defaultBankDetails(), request.getBankDetails()));
            if (replace || request.getSettlementTerms() != null) user.setSettlementTerms(request.getSettlementTerms() == null
                    ? defaultSettlementTerms() : merge(defaultSettlementTerms(), request.getSettlementTerms()));
        }
        Boolean desiredBlocked = replace ? (request.getIsBlocked() != null ? request.getIsBlocked() : Boolean.FALSE) : request.getIsBlocked();
        Boolean desiredDeleted = replace ? (request.getIsDeleted() != null ? request.getIsDeleted() : Boolean.FALSE) : request.getIsDeleted();
        user.setIsProfileComplete(user.hasCompleteProfile());
        user.setUpdatedAt(LocalDateTime.now());
        try {
            User saved = users.save(user);
            if (desiredBlocked != null && !Objects.equals(desiredBlocked, saved.getIsBlocked())) {
                setBlockedStatus(id, desiredBlocked);
            }
            if (desiredDeleted != null && !Objects.equals(desiredDeleted, saved.getIsDeleted())) {
                setDeletedStatus(id, desiredDeleted);
            }
            User finalUser = findUser(id);
            if (finalUser.getRole() == UserRole.ROLE_SELLER && !Objects.equals(priorShopName, finalUser.getShopName())) {
                mongoTemplate.updateMulti(
                        Query.query(Criteria.where("sellerId").is(id)),
                        new Update().set("sellerName", finalUser.getShopName())
                                .set("updatedAt", finalUser.getUpdatedAt())
                                .inc("version", 1),
                        "products");
                redisCache.invalidateRegion("public-products");
                redisCache.invalidateRegion("public-collections");
            }
            return AdminUserResponse.from(finalUser);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new AppException("A user with this email already exists", HttpStatus.CONFLICT);
        }
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> merge(Map<String, Object> current, Map<String, Object> supplied) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        if (current != null) merged.putAll(current);
        if (supplied != null) merged.putAll(supplied);
        return merged;
    }

    private Map<String, Object> defaultBankDetails() {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("accountHolderName", null);
        values.put("bankName", null);
        values.put("accountNumber", null);
        values.put("ifscCode", null);
        values.put("branch", null);
        values.put("upiId", null);
        values.put("isVerified", false);
        return values;
    }

    private Map<String, Object> defaultSettlementTerms() {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("commissionPercentage", 0);
        values.put("settlementCycle", null);
        values.put("payoutStatus", null);
        return values;
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
        List<AdminUserResponse> results = setDeletedStatusMany(List.of(id), deleted);
        return results.get(0);
    }

    @Override
    public List<AdminUserResponse> setDeletedStatusMany(List<String> ids, boolean deleted) {
        if (ids == null || ids.isEmpty()) {
            throw new AppException("At least one userId is required", HttpStatus.BAD_REQUEST);
        }

        List<String> distinctIds = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        if (distinctIds.isEmpty()) {
            throw new AppException("At least one valid userId is required", HttpStatus.BAD_REQUEST);
        }

        List<User> targetUsers = new ArrayList<>(distinctIds.size());
        for (String id : distinctIds) {
            User user = findUser(id);
            ensureCustomerOrSellerLifecycle(user);
            boolean isTransitionToDeleted = deleted && !Boolean.TRUE.equals(user.getIsDeleted());
            if (isTransitionToDeleted
                    && user.getRole() == UserRole.ROLE_ADMIN
                    && users.countActiveByRole(UserRole.ROLE_ADMIN) <= 1) {
                throw new AppException("The last active admin account cannot be deleted", HttpStatus.CONFLICT);
            }
            targetUsers.add(user);
        }

        List<AdminUserResponse> responses = new ArrayList<>(targetUsers.size());
        for (User user : targetUsers) {
            String id = user.getId();
            boolean isTransitionToDeleted = deleted && !Boolean.TRUE.equals(user.getIsDeleted());
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
            responses.add(AdminUserResponse.from(saved));
        }
        return responses;
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
    public PermanentDeleteUsersResponse permanentlyDelete(String adminId, String id) {
        return permanentlyDeleteMany(adminId, List.of(id));
    }

    @Override
    @Transactional
    public PermanentDeleteUsersResponse permanentlyDeleteMany(String adminId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new AppException("At least one userId is required for permanent deletion", HttpStatus.BAD_REQUEST);
        }

        List<String> distinctIds = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        if (distinctIds.isEmpty()) {
            throw new AppException("At least one valid userId is required for permanent deletion", HttpStatus.BAD_REQUEST);
        }

        List<User> targetUsers = new ArrayList<>();

        for (String id : distinctIds) {
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

            targetUsers.add(user);
        }

        List<String> deletedIds = new ArrayList<>();
        for (User user : targetUsers) {
            String id = user.getId();
            mongoTemplate.remove(Query.query(Criteria.where("user").is(id)), Cart.class);
            mongoTemplate.remove(Query.query(Criteria.where("user").is(id)), Wishlist.class);

            users.delete(user);
            webSocketService.sendToUser(id, "auth:session_revoked", Map.of(
                    "userId", id,
                    "reason", "Account has been permanently deleted"
            ));
            deletedIds.add(id);
        }

        return PermanentDeleteUsersResponse.of(deletedIds);
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
