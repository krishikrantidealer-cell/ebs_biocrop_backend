package com.ebs.biocrop.controller;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.dto.request.PlaceOrderRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.OrderResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
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
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int resolvedPage = PageRequestSupport.resolvePageNumber(page);
        int resolvedSize = PageRequestSupport.resolvePageSize(page, size);
        Page<Order> result = orderQueries.listForAdmin(sellerId, resolvedPage, resolvedSize);
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

    // Resolve one checkout identifier to its single order document.
    @GetMapping("/checkout/{checkoutGroupId}")
    @Operation(summary = "Get one checkout order by checkout ID for admin review")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrdersByCheckoutGroup(
            @PathVariable String checkoutGroupId) {
        return ResponseEntity.ok(ApiResponse.ok("Checkout order retrieved",
                OrderResponse.forAdmin(orderQueries.getCheckoutGroupForAdmin(checkoutGroupId))));
    }

    // Cancel an unpaid order; this initial flow does not reserve stock or handle refunds.
    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel an eligible unpaid order")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String orderId) {
        rateLimitGuard.enforce("admin-order-write", admin.getId(), 10, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Order cancelled",
                OrderResponse.forAdmin(orderLifecycle.cancelForAdmin(admin.getId(), orderId))));
    }

    // Create one checkout order for a customer from their cart.
    @PostMapping("/customers/{customerId}/create")
    @Operation(summary = "Create one order from a customer's cart using 10% advance and remaining COD")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrdersForCustomer(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String customerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        rateLimitGuard.enforce("order-create-admin", admin.getId(), 10, Duration.ofMinutes(1));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Order created by admin; awaiting advance payment",
                OrderResponse.forAdmin(orderPlacement.createOrdersForAdmin(customerId, request, admin.getId(), idempotencyKey))));
    }
}
