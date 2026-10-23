package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.dto.response.AdminUserResponse;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.AdminUserService;
import com.ebs.biocrop.service.UserService;
import com.ebs.biocrop.service.RedisJsonCache;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.mongodb.core.query.Update;

@Service
public class AdminUserServiceImpl implements AdminUserService {
    private final MongoTemplate mongo;
    private final UserRepository users;
    private final UserService userService;
    private final RedisJsonCache redisCache;
    public AdminUserServiceImpl(MongoTemplate mongo, UserRepository users,
                               UserService userService, RedisJsonCache redisCache) {
        this.mongo=mongo; this.users=users; this.userService=userService; this.redisCache=redisCache;
    }

    @Override public Page<AdminUserResponse> listAdminUsers(String role, String text, Boolean blocked, Boolean deleted, int page, int size) {
        List<Criteria> filters=new ArrayList<>();
        if (role!=null && !role.isBlank() && !"ALL".equalsIgnoreCase(role)) {
            UserRole parsed;
            String normalizedRole = role.trim().toUpperCase(Locale.ROOT);
            if (normalizedRole.equals("CUSTOMERS")) normalizedRole = "CUSTOMER";
            if (normalizedRole.equals("SELLERS")) normalizedRole = "SELLER";
            try { parsed=UserRole.valueOf(normalizedRole.startsWith("ROLE_") ? normalizedRole : "ROLE_"+normalizedRole); }
            catch (IllegalArgumentException e) { throw new AppException("Role must be CUSTOMER, SELLER, ADMIN, or ALL", HttpStatus.BAD_REQUEST); }
            filters.add(Criteria.where("role").is(parsed));
        }
        if (blocked!=null) filters.add(blocked ? Criteria.where("isBlocked").is(true) : new Criteria().orOperator(Criteria.where("isBlocked").is(false), Criteria.where("isBlocked").exists(false), Criteria.where("isBlocked").is(null)));
        if (deleted!=null) filters.add(deleted ? Criteria.where("isDeleted").is(true) : new Criteria().orOperator(Criteria.where("isDeleted").is(false), Criteria.where("isDeleted").exists(false), Criteria.where("isDeleted").is(null)));
        if (text!=null && !text.isBlank()) {
            if (text.trim().length() > 100) throw new AppException("Search query must be at most 100 characters", HttpStatus.BAD_REQUEST);
            String rx="^"+java.util.regex.Pattern.quote(text.trim());
            filters.add(new Criteria().orOperator(Criteria.where("firstName").regex(rx,"i"), Criteria.where("lastName").regex(rx,"i"),
                    Criteria.where("phoneNumber").regex(rx,"i"), Criteria.where("email").regex(rx,"i"), Criteria.where("shopName").regex(rx,"i")));
        }
        Pageable pageable=PageRequestSupport.create(page,size,Sort.by(Sort.Direction.DESC,"createdAt"));
        Query q=new Query(filters.isEmpty()?new Criteria():new Criteria().andOperator(filters.toArray(Criteria[]::new))).with(pageable);
        List<AdminUserResponse> content=mongo.find(q,User.class).stream().map(AdminUserResponse::from).toList();
        long total=mongo.count(Query.of(q).limit(-1).skip(-1),User.class);
        return new PageImpl<>(content,pageable,total);
    }
    @Override public AdminUserResponse getAdminUserById(String id) { return AdminUserResponse.from(find(id)); }
    @Override public AdminUserResponse setAdminUserBlockedStatus(String id, boolean blocked) {
        User u=find(id); if (u.getRole()==UserRole.ROLE_ADMIN && blocked && users.countActiveByRole(UserRole.ROLE_ADMIN)<=1)
            throw new AppException("The last active admin account cannot be blocked",HttpStatus.CONFLICT);
        if (blocked && u.getRole()==UserRole.ROLE_SELLER) deactivateSellerListings(id);
        u.setIsBlocked(blocked); u.setUpdatedAt(LocalDateTime.now());
        User saved=users.save(u);
        return AdminUserResponse.from(saved);
    }
    @Override public AdminUserResponse setAdminUserDeletedStatus(String id, boolean deleted) {
        User u=find(id); if (u.getRole()==UserRole.ROLE_ADMIN && deleted && !Boolean.TRUE.equals(u.getIsDeleted()) && users.countActiveByRole(UserRole.ROLE_ADMIN)<=1)
            throw new AppException("The last active admin account cannot be deleted",HttpStatus.CONFLICT);
        if (deleted && u.getRole()==UserRole.ROLE_SELLER) deactivateSellerListings(id);
        u.setIsDeleted(deleted); u.setUpdatedAt(LocalDateTime.now());
        User saved=users.save(u);
        return AdminUserResponse.from(saved);
    }
    @Override public UserRole assignAdminUserRole(String id, String requestedRole) {
        User existing=find(id);
        UserRole requested;
        try { requested=UserRole.valueOf(requestedRole.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException e) { throw new AppException("Role must be ROLE_CUSTOMER, ROLE_SELLER, or ROLE_ADMIN",HttpStatus.BAD_REQUEST); }
        if (existing.getRole()==UserRole.ROLE_SELLER && requested!=UserRole.ROLE_SELLER) deactivateSellerListings(id);
        UserRole role=userService.assignRole(id,requestedRole);
        return role;
    }
    private void deactivateSellerListings(String sellerId) {
        mongo.updateMulti(Query.query(Criteria.where("sellerId").is(sellerId)),
                new Update().set("status","INACTIVE").set("isAvailable",false).set("isFeatured",false)
                        .set("updatedAt",LocalDateTime.now()), "products");
        redisCache.invalidateRegion("public-products");
        redisCache.invalidateRegion("public-collections");
    }
    private User find(String id) { return users.findById(id).orElseThrow(()->new ResourceNotFoundException("User","id",id)); }
}
