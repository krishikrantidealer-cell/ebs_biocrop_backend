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
import com.ebs.biocrop.service.GcsSellerKycDocumentService;
import com.ebs.biocrop.service.RedisJsonCache;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final SellerCodeService sellerCodeService;
    @Autowired(required = false)
    private PasswordEncoder passwordEncoder;
    @Autowired(required = false)
    private GcsSellerKycDocumentService sellerKycDocumentService;

    @Autowired
    public AdminUserServiceImpl(
            MongoTemplate mongoTemplate,
            UserRepository users,
            RedisJsonCache redisCache,
            WebSocketService webSocketService,
            SellerCodeService sellerCodeService) {
        this.mongoTemplate = mongoTemplate;
        this.users = users;
        this.redisCache = redisCache;
        this.webSocketService = webSocketService;
        this.sellerCodeService = sellerCodeService;
    }

    /** Keeps direct construction in existing unit tests source-compatible. */
    public AdminUserServiceImpl(MongoTemplate mongoTemplate, UserRepository users,
                                RedisJsonCache redisCache, WebSocketService webSocketService) {
        this(mongoTemplate, users, redisCache, webSocketService, null);
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
        if (role == UserRole.ROLE_SELLER && (isBlank(request.getFirstName()) || isBlank(request.getLastName())
                || isBlank(request.getShopName()) || isBlank(email))) {
            throw new AppException("Seller first name, last name, phone, email, and shop name are required", HttpStatus.BAD_REQUEST);
        }
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setAddress(request.getAddress());
        if (role == UserRole.ROLE_CUSTOMER) {
            user.setShippingAddresses(request.getShippingAddresses() == null
                    ? new ArrayList<>() : new ArrayList<>(request.getShippingAddresses()));
            if (request.getNotesHistory() != null) user.setNotesHistory(new ArrayList<>(request.getNotesHistory()));
            if (request.getIsVerified() != null) user.setIsVerified(request.getIsVerified());
        }
        user.setStatus(request.getStatus() == null ? "ACTIVE" : request.getStatus());
        user.setNotes(request.getNotes());
        user.setIsBlocked(Boolean.TRUE.equals(request.getIsBlocked()));
        user.setIsDeleted(Boolean.TRUE.equals(request.getIsDeleted()));
        if (role != UserRole.ROLE_CUSTOMER && request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(encodePassword(request.getPassword()));
        }
        if (role == UserRole.ROLE_SELLER) {
            user.setShopName(request.getShopName());
            user.setGstNumber(request.getGstNumber());
            user.setPanNumber(request.getPanNumber());
            user.setLicenceNo(request.getLicenceNo());
            user.setWarehouseAddresses(request.getWarehouseAddresses() == null
                    ? new ArrayList<>() : new ArrayList<>(request.getWarehouseAddresses()));
            user.setMonthlyTarget(request.getMonthlyTarget());
            user.setPreferredLanguage(request.getPreferredLanguage());
            if (request.getBankDetails() != null) user.setBankDetails(merge(user.getBankDetails(), request.getBankDetails()));
            if (request.getSettlementTerms() != null) user.setSettlementTerms(merge(user.getSettlementTerms(), request.getSettlementTerms()));
            applySellerPricingModel(user, request.getPricingModel(), request.getCommissionPercentage(), true);
        }
        user.setIsProfileComplete(user.hasCompleteProfile());
        user.setUpdatedAt(LocalDateTime.now());
        try {
            User saved = users.save(user);
            if (role == UserRole.ROLE_SELLER && sellerCodeService != null) {
                sellerCodeService.ensureSellerCode(saved.getId());
                saved = users.findById(saved.getId()).orElse(saved);
            }
            return AdminUserResponse.from(saved);
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
        if (user.getRole() == UserRole.ROLE_CUSTOMER && (replace || request.getShippingAddresses() != null)) {
            user.setShippingAddresses(request.getShippingAddresses() == null
                    ? new ArrayList<>() : new ArrayList<>(request.getShippingAddresses()));
        }
        if (replace || request.getStatus() != null) user.setStatus(request.getStatus() == null ? "ACTIVE" : request.getStatus());
        if (replace || request.getNotes() != null) user.setNotes(request.getNotes());
        if (user.getRole() == UserRole.ROLE_CUSTOMER) {
            if (replace || request.getNotesHistory() != null) user.setNotesHistory(
                    request.getNotesHistory() == null ? new ArrayList<>() : new ArrayList<>(request.getNotesHistory()));
            if (replace || request.getIsVerified() != null) user.setIsVerified(Boolean.TRUE.equals(request.getIsVerified()));
        }
        if (user.getRole() != UserRole.ROLE_CUSTOMER && request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(encodePassword(request.getPassword()));
        }
        String priorShopName = user.getShopName();
        String priorPricingModel = user.getPricingModel();
        Double priorCommission = user.getCommissionPercentage();
        if (user.getRole() == UserRole.ROLE_SELLER) {
            if (replace || request.getShopName() != null) user.setShopName(request.getShopName());
            if (replace || request.getGstNumber() != null) user.setGstNumber(request.getGstNumber());
            if (replace || request.getPanNumber() != null) user.setPanNumber(request.getPanNumber());
            if (replace || request.getLicenceNo() != null) user.setLicenceNo(request.getLicenceNo());
            if (replace || request.getWarehouseAddresses() != null) user.setWarehouseAddresses(
                    request.getWarehouseAddresses() == null ? new ArrayList<>() : new ArrayList<>(request.getWarehouseAddresses()));
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
            if (priorPricingModel != null && !priorPricingModel.isBlank()
                    && request.getPricingModel() != null && !request.getPricingModel().isBlank()
                    && !priorPricingModel.trim().equalsIgnoreCase(request.getPricingModel().trim())) {
                throw new AppException("Seller pricing model cannot be changed once set", HttpStatus.CONFLICT);
            }
            applySellerPricingModel(user, request.getPricingModel(), request.getCommissionPercentage(), replace);
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
            syncSellerProductsPricingIfChanged(finalUser, priorPricingModel, priorCommission);
            return AdminUserResponse.from(finalUser);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new AppException("A user with this email already exists", HttpStatus.CONFLICT);
        }
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) { return value == null || value.isBlank(); }

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
        values.put("cancelledCheque", null);
        values.put("isVerified", false);
        return values;
    }

    private String encodePassword(String rawPassword) {
        PasswordEncoder encoder = this.passwordEncoder != null ? this.passwordEncoder : new BCryptPasswordEncoder();
        return encoder.encode(rawPassword);
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
            webSocketService.disconnectUser(id);
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
                webSocketService.disconnectUser(id);
            }
            responses.add(AdminUserResponse.from(saved));
        }
        return responses;
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
            if (user.getRole() == UserRole.ROLE_SELLER && sellerKycDocumentService != null) {
                sellerKycDocumentService.deleteAllForSeller(id);
            }
            webSocketService.sendToUser(id, "auth:session_revoked", Map.of(
                    "userId", id,
                    "reason", "Account has been permanently deleted"
            ));
            webSocketService.disconnectUser(id);
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

    private void applySellerPricingModel(
            User user, String requestedModel, Double requestedCommission, boolean defaultIfMissing) {
        String effectiveModel = requestedModel;
        if (effectiveModel == null || effectiveModel.isBlank()) {
            if (defaultIfMissing && (user.getPricingModel() == null || user.getPricingModel().isBlank())) {
                effectiveModel = "MARKETPLACE";
            } else {
                effectiveModel = user.getPricingModel() == null || user.getPricingModel().isBlank()
                        ? "MARKETPLACE" : user.getPricingModel();
            }
        } else {
            effectiveModel = effectiveModel.trim().toUpperCase(Locale.ROOT);
        }

        if (!"MARKETPLACE".equals(effectiveModel) && !"DEALER_COMMISSION".equals(effectiveModel)) {
            throw new AppException("Pricing model must be MARKETPLACE or DEALER_COMMISSION", HttpStatus.BAD_REQUEST);
        }

        Map<String, Object> terms = user.getSettlementTerms() != null
                ? new java.util.LinkedHashMap<>(user.getSettlementTerms())
                : defaultSettlementTerms();

        if ("DEALER_COMMISSION".equals(effectiveModel)) {
            Double comm = requestedCommission != null ? requestedCommission : user.getCommissionPercentage();
            if (comm == null || !Double.isFinite(comm) || comm < 0.0 || comm > 100.0) {
                throw new AppException("Commission percentage must be between 0 and 100 for Dealer Commission",
                        HttpStatus.BAD_REQUEST);
            }
            user.setPricingModel("DEALER_COMMISSION");
            user.setCommissionPercentage(comm);
            terms.put("commissionPercentage", comm);
        } else {
            user.setPricingModel("MARKETPLACE");
            user.setCommissionPercentage(0.0);
            if (requestedModel != null) {
                terms.put("commissionPercentage", 0.0);
            }
        }
        user.setSettlementTerms(terms);
    }

    private void syncSellerProductsPricingIfChanged(
            User seller, String priorPricingModel, Double priorCommission) {
        if (seller == null || seller.getRole() != UserRole.ROLE_SELLER) return;
        boolean modelChanged = !Objects.equals(priorPricingModel, seller.getPricingModel());
        boolean commissionChanged = !Objects.equals(priorCommission, seller.getCommissionPercentage());
        if (!modelChanged && !commissionChanged) return;

        boolean isDealerCommission = "DEALER_COMMISSION".equalsIgnoreCase(seller.getPricingModel());
        if (!modelChanged && !isDealerCommission) return;

        double newComm = isDealerCommission && seller.getCommissionPercentage() != null
                ? seller.getCommissionPercentage() : 0.0;
        List<Product> sellerProducts = mongoTemplate.find(
                Query.query(Criteria.where("sellerId").is(seller.getId())),
                Product.class);
        if (sellerProducts.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        for (Product product : sellerProducts) {
            product.setIsMarketplace(!isDealerCommission);
            if (product.getVariants() != null) {
                for (com.ebs.biocrop.entity.ProductVariant variant : product.getVariants()) {
                    if (modelChanged) {
                        // Switching pricing models invalidates prior pricing/stock so admin re-enters valid values.
                        variant.setSwg(0.0);
                        variant.setDisplayRate(0.0);
                        variant.setPrintedMrp(0.0);
                        variant.setCourierCharges(0.0);
                        variant.setCodCharge(0.0);
                        variant.setDiscountOnMrpRs(0.0);
                        variant.setDiscountPercentage(0.0);
                        variant.setStock(0);
                        variant.setCommissionPercentage(newComm);
                    } else {
                        variant.setCommissionPercentage(newComm);
                        variant.setCourierCharges(0.0);
                        variant.setCodCharge(0.0);
                        if (variant.getDisplayRate() != null && variant.getDisplayRate() > 0) {
                            double updatedSwg = variant.getDisplayRate() * (1.0 - newComm / 100.0);
                            variant.setSwg(Math.round(updatedSwg * 100.0) / 100.0);
                        }
                    }
                }
            }
            if (modelChanged) {
                // Cleaned variants have zero stock/pricing, so take the product offline until re-priced.
                product.setIsAvailable(false);
            }
            product.setUpdatedAt(now);
            mongoTemplate.save(product);
        }
        redisCache.invalidateRegion("public-products");
        redisCache.invalidateRegion("public-collections");
    }

    private User findUser(String id) {
        return users.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }
}
