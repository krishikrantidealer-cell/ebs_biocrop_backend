package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.PlaceOrderRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.OrderResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.PlacedOrdersResponse;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.service.OrderPlacementService;
import com.ebs.biocrop.service.OrderQueryService;
import com.ebs.biocrop.service.RateLimitGuard;
import com.ebs.biocrop.security.user.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin orders")
@SecurityRequirement(name = "bearerAuth")
public class AdminOrderController {
    private final OrderPlacementService orderPlacement;
    private final OrderQueryService orderQueries;
    private final RateLimitGuard rateLimitGuard;

    public AdminOrderController(OrderPlacementService orderPlacement, OrderQueryService orderQueries,
                                RateLimitGuard rateLimitGuard) {
        this.orderPlacement = orderPlacement;
        this.orderQueries = orderQueries;
        this.rateLimitGuard = rateLimitGuard;
    }

    @GetMapping
    @Operation(summary = "List all orders for admin with pagination")
    public ResponseEntity<ApiResponse<PagedResponse<OrderResponse>>> getAllOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Order> result = orderQueries.listForAdmin(page, size);
        return ResponseEntity.ok(ApiResponse.ok("Admin orders retrieved",
                PagedResponse.from(result, OrderResponse::forAdmin)));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get any order by ID for admin review")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderById(@PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.ok("Order retrieved",
                OrderResponse.forAdmin(orderQueries.getForAdmin(orderId))));
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
