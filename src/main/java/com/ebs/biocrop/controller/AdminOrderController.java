package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.PlaceOrderRequest;
import com.ebs.biocrop.dto.request.UpdateOrderStatusRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.CountedListResponse;
import com.ebs.biocrop.dto.response.OrderResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.response.PlacedOrdersResponse;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.service.OrderPlacementService;
import com.ebs.biocrop.service.OrderQueryService;
import com.ebs.biocrop.service.OrderLifecycleService;
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
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final OrderLifecycleService orderLifecycle;
    private final RateLimitGuard rateLimitGuard;

    public AdminOrderController(OrderPlacementService orderPlacement, OrderQueryService orderQueries,
                                OrderLifecycleService orderLifecycle,
                                RateLimitGuard rateLimitGuard) {
        this.orderPlacement = orderPlacement;
        this.orderQueries = orderQueries;
        this.orderLifecycle = orderLifecycle;
        this.rateLimitGuard = rateLimitGuard;
    }

    // List all orders by sellerID or without sellerID for admin with pagination
    @GetMapping
    @Operation(summary = "List all orders for admin with pagination, optionally filtered by seller ID")
    public ResponseEntity<ApiResponse<PagedResponse<OrderResponse>>> getOrders(
            @RequestParam(required = false) String sellerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Order> result = orderQueries.listForAdmin(sellerId, page, size);
        return ResponseEntity.ok(ApiResponse.ok("Admin orders retrieved",
                PagedResponse.from(result, OrderResponse::forAdmin)));
    }

    // Get a specific order by ID
    @GetMapping("/{orderId}")
    @Operation(summary = "Get any order by ID for admin review")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderById(@PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.ok("Order retrieved",
                OrderResponse.forAdmin(orderQueries.getForAdmin(orderId))));
    }

    // List all seller orders in a checkout group for admin review
    @GetMapping("/group/{checkoutGroupId}")
    @Operation(summary = "List all seller orders from one checkout group for admin review")
    public ResponseEntity<ApiResponse<CountedListResponse<OrderResponse>>> getOrdersByCheckoutGroup(
            @PathVariable String checkoutGroupId) {
        return ResponseEntity.ok(ApiResponse.ok("Checkout orders retrieved",
                CountedListResponse.from(orderQueries.getCheckoutGroupForAdmin(checkoutGroupId).stream()
                        .map(OrderResponse::forAdmin).toList())));
    }

    // Update the status of an order
    @PatchMapping("/{orderId}/status")
    @Operation(summary = "Advance an order through its fulfillment lifecycle")
    public ResponseEntity<ApiResponse<OrderResponse>> updateOrderStatus(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String orderId,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        rateLimitGuard.enforce("admin-order-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Order status updated",
                OrderResponse.forAdmin(orderLifecycle.updateStatusForAdmin(
                        admin.getId(), orderId, request.status(), request.note()))));
    }

    // Cancel an order and release its reserved stock
    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel an eligible order and release its reserved stock")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String orderId) {
        rateLimitGuard.enforce("admin-order-write", admin.getId(), 10, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Order cancelled",
                OrderResponse.forAdmin(orderLifecycle.cancelForAdmin(admin.getId(), orderId))));
    }

    // Create orders for a customer from their cart with an admin-approved advance percentage
    @PostMapping("/customers/{customerId}/create")
    @Operation(summary = "Create orders from a customer's cart with an admin-approved advance percentage")
    public ResponseEntity<ApiResponse<PlacedOrdersResponse>> createOrdersForCustomer(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String customerId,
            @Valid @RequestBody PlaceOrderRequest request) {
        rateLimitGuard.enforce("order-create-admin", admin.getId(), 10, Duration.ofMinutes(1));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Orders placed by admin",
                orderPlacement.createOrdersForAdmin(customerId, request, admin.getId())));
    }
}
