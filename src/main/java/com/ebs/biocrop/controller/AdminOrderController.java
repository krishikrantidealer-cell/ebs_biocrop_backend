package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.PlaceOrderRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PlacedOrdersResponse;
import com.ebs.biocrop.service.OrderPlacementService;
import com.ebs.biocrop.service.RateLimitGuard;
import com.ebs.biocrop.security.user.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin orders")
@SecurityRequirement(name = "bearerAuth")
public class AdminOrderController {
    private final OrderPlacementService orderPlacement;
    private final RateLimitGuard rateLimitGuard;

    public AdminOrderController(OrderPlacementService orderPlacement, RateLimitGuard rateLimitGuard) {
        this.orderPlacement = orderPlacement;
        this.rateLimitGuard = rateLimitGuard;
    }

    @PostMapping("/customers/{customerId}/create")
    @Operation(summary = "Create orders from a customer's cart with an admin-approved advance percentage")
    public ResponseEntity<ApiResponse<PlacedOrdersResponse>> createCustomerOrders(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String customerId,
            @Valid @RequestBody PlaceOrderRequest request) {
        rateLimitGuard.enforce("order-create-admin", admin.getId(), 10, Duration.ofMinutes(1));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Orders placed by admin",
                orderPlacement.createOrdersForAdmin(customerId, request, admin.getId())));
    }
}
