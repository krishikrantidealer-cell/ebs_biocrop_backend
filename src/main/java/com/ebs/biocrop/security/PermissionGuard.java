package com.ebs.biocrop.security;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.user.CustomUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Evaluates granular RBAC permissions for ROLE_SALES_AGENT against live MongoDB user state,
 * while granting ROLE_ADMIN unrestricted administrative access.
 */
@Component("permissionGuard")
public class PermissionGuard {

    public static final Set<String> ALL_SALES_PERMISSIONS = Set.of(
            "DASHBOARD_VIEW",
            "CATALOG_VIEW",
            "CATALOG_MANAGE",
            "USERS_SELLERS_VIEW",
            "USERS_CUSTOMERS_VIEW",
            "USERS_CUSTOMERS_MANAGE",
            "ORDERS_VIEW",
            "ORDERS_CREATE",
            "ORDERS_MANAGE"
    );

    public static final Set<String> ALL_MARKETING_PERMISSIONS = Set.of(
            "MARKETING_VIEW",
            "MARKETING_MANAGE",
            "ENGAGEMENT_VIEW",
            "ENGAGEMENT_MANAGE",
            "CATALOG_VIEW",
            "CATALOG_MANAGE"
    );

    public static final Set<String> ALL_STAFF_PERMISSIONS = Set.of(
            "DASHBOARD_VIEW",
            "CATALOG_VIEW",
            "CATALOG_MANAGE",
            "USERS_SELLERS_VIEW",
            "USERS_CUSTOMERS_VIEW",
            "USERS_CUSTOMERS_MANAGE",
            "ORDERS_VIEW",
            "ORDERS_CREATE",
            "ORDERS_MANAGE",
            "MARKETING_VIEW",
            "MARKETING_MANAGE",
            "ENGAGEMENT_VIEW",
            "ENGAGEMENT_MANAGE"
    );

    private final UserRepository userRepository;

    public PermissionGuard(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean has(Authentication authentication, String permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (isAdmin) {
            return true;
        }
        boolean isStaff = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SALES_AGENT".equals(a.getAuthority())
                        || "ROLE_MARKETING".equals(a.getAuthority()));
        if (!isStaff) {
            return false;
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof CustomUserDetails details) || details.getId() == null) {
            return false;
        }
        User user = userRepository.findById(details.getId()).orElse(null);
        if (user == null
                || (user.getRole() != UserRole.ROLE_SALES_AGENT && user.getRole() != UserRole.ROLE_MARKETING)
                || Boolean.TRUE.equals(user.getIsDeleted())
                || Boolean.TRUE.equals(user.getIsBlocked())
                || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            return false;
        }
        return user.getPermissions().contains(permission);
    }

    public boolean hasAny(Authentication authentication, String... permissions) {
        if (permissions == null || permissions.length == 0) {
            return false;
        }
        for (String p : permissions) {
            if (has(authentication, p)) {
                return true;
            }
        }
        return false;
    }
}
