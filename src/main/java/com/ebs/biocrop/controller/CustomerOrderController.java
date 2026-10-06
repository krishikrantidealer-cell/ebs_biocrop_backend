package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.request.PlaceOrderRequest;
import com.ebs.biocrop.dto.response.PlacedOrdersResponse;
import com.ebs.biocrop.dto.response.OrderResponse;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.OrderQueryService;
import com.ebs.biocrop.service.OrderPlacementService;
import com.ebs.biocrop.service.OrderLifecycleService;
import com.ebs.biocrop.service.RateLimitGuard;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/orders")
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Customer orders", description = "Orders belonging to the authenticated customer.")
@SecurityRequirement(name = "bearerAuth")
public class CustomerOrderController {
    private final OrderQueryService orderQueries;
    private final OrderPlacementService orderPlacement;
    private final OrderLifecycleService orderLifecycle;
    private final RateLimitGuard rateLimitGuard;

    public CustomerOrderController(OrderQueryService orderQueries, OrderPlacementService orderPlacement,
                                   OrderLifecycleService orderLifecycle, RateLimitGuard rateLimitGuard) {
        this.orderQueries = orderQueries;
        this.orderPlacement = orderPlacement;
        this.orderLifecycle = orderLifecycle;
        this.rateLimitGuard = rateLimitGuard;
    }

    @PostMapping("/create")
    @Operation(summary = "Create seller-specific orders from the authenticated customer's cart")
    public ResponseEntity<ApiResponse<PlacedOrdersResponse>> createOrders(
            @AuthenticationPrincipal CustomUserDetails customer,
            @Valid @RequestBody PlaceOrderRequest request) {
        rateLimitGuard.enforce("order-create-customer", customer.getId(), 5, Duration.ofMinutes(1));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Orders placed",
                orderPlacement.createOrders(customer.getId(), request)));
    }

    @GetMapping("/customer")
    @Operation(summary = "List the authenticated customer's orders")
    public ResponseEntity<ApiResponse<PagedResponse<OrderResponse>>> getCustomerOrders(
            @AuthenticationPrincipal CustomUserDetails customer,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Order> result = orderQueries.listForCustomer(customer.getId(), page, size);
        return ResponseEntity.ok(ApiResponse.ok("Customer orders retrieved",
                PagedResponse.from(result, OrderResponse::forCustomer)));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get one of the authenticated customer's orders")
    public ResponseEntity<ApiResponse<OrderResponse>> getCustomerOrderById(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.ok("Order retrieved",
                OrderResponse.forCustomer(orderQueries.getForCustomer(customer.getId(), orderId))));
    }

    @GetMapping("/group/{checkoutGroupId}")
    @Operation(summary = "List the authenticated customer's seller orders from one checkout")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getOrdersByCheckoutGroup(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String checkoutGroupId) {
        return ResponseEntity.ok(ApiResponse.ok("Checkout orders retrieved",
                orderQueries.getCheckoutGroupForCustomer(customer.getId(), checkoutGroupId).stream()
                        .map(OrderResponse::forCustomer).toList()));
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel a pending order owned by the authenticated customer")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelCustomerOrder(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String orderId) {
        rateLimitGuard.enforce("order-cancel", customer.getId(), 10, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Order cancelled",
                OrderResponse.forCustomer(orderLifecycle.cancelForCustomer(customer.getId(), orderId))));
    }
}
