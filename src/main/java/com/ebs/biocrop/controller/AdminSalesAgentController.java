package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.PermissionGuard;
import com.ebs.biocrop.websocket.WebSocketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/sales-agents")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Sales Agents & RBAC", description = "Create sales agents and manage granular feature permissions")
@SecurityRequirement(name = "bearerAuth")
public class AdminSalesAgentController {

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789@#!";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;
    private final PasswordEncoder passwordEncoder;
    private final WebSocketService webSocketService;

    public AdminSalesAgentController(
            UserRepository userRepository,
            MongoTemplate mongoTemplate,
            PasswordEncoder passwordEncoder,
            WebSocketService webSocketService) {
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
        this.passwordEncoder = passwordEncoder;
        this.webSocketService = webSocketService;
    }

    @GetMapping
    @Operation(summary = "List all staff accounts (Sales Agents & Marketing Managers) and available RBAC permissions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listSalesAgents(
            @RequestParam(name = "role", required = false) String roleFilter) {
        List<String> targetRoles = (roleFilter != null && !roleFilter.isBlank())
                ? List.of(UserRole.fromString(roleFilter).name())
                : List.of(UserRole.ROLE_SALES_AGENT.name(), UserRole.ROLE_MARKETING.name());

        List<User> agents = mongoTemplate.find(
                Query.query(Criteria.where("role").in(targetRoles)
                        .and("isDeleted").ne(true)),
                User.class);
        agents.sort(Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        List<Map<String, Object>> items = agents.stream()
                .map(u -> toAgentDto(u, null))
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agents", items);
        data.put("availablePermissions", PermissionGuard.ALL_STAFF_PERMISSIONS);
        data.put("salesPermissions", PermissionGuard.ALL_SALES_PERMISSIONS);
        data.put("marketingPermissions", PermissionGuard.ALL_MARKETING_PERMISSIONS);
        return ResponseEntity.ok(ApiResponse.ok("Staff accounts retrieved", data));
    }

    @PostMapping
    @Operation(summary = "Create a new staff account (Sales Agent or Marketing) with email/password and permissions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSalesAgent(
            @Valid @RequestBody CreateSalesAgentRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new AppException("An account with this email already exists", HttpStatus.CONFLICT);
        }

        String phone = request.phoneNumber() == null ? "" : request.phoneNumber().trim();
        if (!phone.isEmpty() && userRepository.findByPhoneNumber(phone).isPresent()) {
            throw new AppException("An account with this phone number already exists", HttpStatus.CONFLICT);
        }
        if (phone.isEmpty()) {
            phone = generateUniqueSyntheticPhone();
        }

        UserRole targetRole = (request.role() != null && !request.role().isBlank())
                ? UserRole.fromString(request.role())
                : UserRole.ROLE_SALES_AGENT;
        if (targetRole != UserRole.ROLE_SALES_AGENT && targetRole != UserRole.ROLE_MARKETING) {
            targetRole = UserRole.ROLE_SALES_AGENT;
        }

        String rawPassword = request.password() != null && !request.password().isBlank()
                ? request.password().trim()
                : generateRandomPassword(10);

        User agent = new User(phone, targetRole);
        agent.setEmail(email);
        agent.setFirstName(request.firstName().trim());
        agent.setLastName(request.lastName() == null ? "" : request.lastName().trim());
        agent.setPasswordHash(passwordEncoder.encode(rawPassword));
        Set<String> perms = sanitizePermissions(request.permissions());
        if (perms.isEmpty() && targetRole == UserRole.ROLE_MARKETING) {
            perms = new LinkedHashSet<>(Set.of(
                    "MARKETING_VIEW",
                    "MARKETING_MANAGE",
                    "ENGAGEMENT_VIEW",
                    "ENGAGEMENT_MANAGE",
                    "CATALOG_VIEW"
            ));
        }
        agent.setPermissions(perms);
        agent.setCreatedAt(LocalDateTime.now());
        agent.setUpdatedAt(LocalDateTime.now());

        User saved = userRepository.save(agent);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Staff account created", toAgentDto(saved, rawPassword)));
    }

    @PatchMapping("/{id}/permissions")
    @Operation(summary = "Update a sales agent's permissions and push live WebSocket update")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updatePermissions(
            @PathVariable String id,
            @RequestBody UpdatePermissionsRequest request) {
        User agent = requireSalesAgent(id);
        Set<String> updatedPerms = sanitizePermissions(request.permissions());
        agent.setPermissions(updatedPerms);
        agent.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(agent);

        boolean active = !Boolean.TRUE.equals(saved.getIsBlocked())
                && !Boolean.TRUE.equals(saved.getIsDeleted())
                && "ACTIVE".equalsIgnoreCase(saved.getStatus());

        webSocketService.sendToUser(saved.getId(), "sales:permissions_updated", Map.of(
                "userId", saved.getId(),
                "permissions", saved.getPermissions(),
                "active", active
        ));

        return ResponseEntity.ok(ApiResponse.ok("Permissions updated", toAgentDto(saved, null)));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Enable/disable a sales agent or reset password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateStatus(
            @PathVariable String id,
            @RequestBody UpdateStatusRequest request) {
        User agent = requireSalesAgent(id);
        String newGeneratedPassword = null;

        if (request.blocked() != null) {
            agent.setIsBlocked(request.blocked());
            agent.setStatus(Boolean.TRUE.equals(request.blocked()) ? "BLOCKED" : "ACTIVE");
            if (Boolean.TRUE.equals(request.blocked())) {
                agent.incrementTokenVersion();
            }
        }

        if (Boolean.TRUE.equals(request.resetPassword())) {
            newGeneratedPassword = (request.newPassword() != null && !request.newPassword().isBlank())
                    ? request.newPassword().trim()
                    : generateRandomPassword(10);
            agent.setPasswordHash(passwordEncoder.encode(newGeneratedPassword));
            agent.incrementTokenVersion();
        }

        agent.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(agent);

        boolean active = !Boolean.TRUE.equals(saved.getIsBlocked())
                && !Boolean.TRUE.equals(saved.getIsDeleted())
                && "ACTIVE".equalsIgnoreCase(saved.getStatus());

        webSocketService.sendToUser(saved.getId(), "sales:permissions_updated", Map.of(
                "userId", saved.getId(),
                "permissions", active ? saved.getPermissions() : Set.of(),
                "active", active
        ));

        return ResponseEntity.ok(ApiResponse.ok("Sales agent updated", toAgentDto(saved, newGeneratedPassword)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate and remove a sales agent")
    public ResponseEntity<ApiResponse<Void>> deleteSalesAgent(@PathVariable String id) {
        User agent = requireSalesAgent(id);
        agent.setIsDeleted(true);
        agent.setStatus("DELETED");
        agent.incrementTokenVersion();
        agent.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(agent);

        webSocketService.sendToUser(saved.getId(), "sales:permissions_updated", Map.of(
                "userId", saved.getId(),
                "permissions", Set.of(),
                "active", false
        ));

        return ResponseEntity.ok(ApiResponse.ok("Sales agent removed", null));
    }

    private User requireSalesAgent(String id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Staff Account", "id", id));
        if ((user.getRole() != UserRole.ROLE_SALES_AGENT && user.getRole() != UserRole.ROLE_MARKETING)
                || Boolean.TRUE.equals(user.getIsDeleted())) {
            throw new ResourceNotFoundException("Staff Account", "id", id);
        }
        return user;
    }

    private Set<String> sanitizePermissions(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Set<String> clean = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) continue;
            String normalized = item.trim().toUpperCase(Locale.ROOT);
            if (PermissionGuard.ALL_STAFF_PERMISSIONS.contains(normalized)) {
                clean.add(normalized);
            }
        }
        return clean;
    }

    private String generateRandomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    private String generateUniqueSyntheticPhone() {
        for (int attempt = 0; attempt < 10; attempt++) {
            long num = 7000000000L + Math.abs(RANDOM.nextLong()) % 2999999999L;
            String candidate = String.valueOf(num);
            if (userRepository.findByPhoneNumber(candidate).isEmpty()) {
                return candidate;
            }
        }
        return "7" + String.valueOf(System.currentTimeMillis()).substring(4);
    }

    private Map<String, Object> toAgentDto(User user, String oneTimePassword) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", user.getId());
        dto.put("firstName", user.getFirstName() != null ? user.getFirstName() : "");
        dto.put("lastName", user.getLastName() != null ? user.getLastName() : "");
        dto.put("email", user.getEmail() != null ? user.getEmail() : "");
        dto.put("phoneNumber", user.getPhoneNumber() != null ? user.getPhoneNumber() : "");
        dto.put("role", user.getRole().name());
        dto.put("status", user.getStatus() != null ? user.getStatus() : "ACTIVE");
        dto.put("isBlocked", Boolean.TRUE.equals(user.getIsBlocked()));
        dto.put("permissions", user.getPermissions());
        dto.put("createdAt", user.getCreatedAt());
        dto.put("updatedAt", user.getUpdatedAt());
        if (oneTimePassword != null) {
            dto.put("generatedPassword", oneTimePassword);
        }
        return dto;
    }

    public record CreateSalesAgentRequest(
            @NotBlank String firstName,
            String lastName,
            @NotBlank @Email String email,
            String phoneNumber,
            String password,
            String role,
            Set<String> permissions
    ) {}

    public record UpdatePermissionsRequest(Set<String> permissions) {}

    public record UpdateStatusRequest(Boolean blocked, Boolean resetPassword, String newPassword) {}
}
