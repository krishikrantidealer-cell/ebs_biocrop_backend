package com.ebs.biocrop.controller;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.dto.request.PlaceOrderRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.OrderResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.service.OrderPlacementService;
import com.ebs.biocrop.service.OrderQueryService;
import com.ebs.biocrop.service.VelocityService;
import com.ebs.biocrop.service.OrderLifecycleService;
import org.springframework.beans.factory.annotation.Autowired;
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
@PreAuthorize("@permissionGuard.hasAny(authentication, 'ORDERS_VIEW', 'ORDERS_CREATE', 'ORDERS_MANAGE')")
@Tag(name = "Admin orders")
@SecurityRequirement(name = "bearerAuth")
public class AdminOrderController {
    private final OrderPlacementService orderPlacement;
    private final OrderQueryService orderQueries;
    private final OrderLifecycleService orderLifecycle;
    private final RateLimitGuard rateLimitGuard;
    private final VelocityService velocityService;

    public AdminOrderController(OrderPlacementService orderPlacement, OrderQueryService orderQueries,
                                OrderLifecycleService orderLifecycle,
                                RateLimitGuard rateLimitGuard) {
        this(orderPlacement, orderQueries, orderLifecycle, rateLimitGuard, null);
    }

    @Autowired
    public AdminOrderController(OrderPlacementService orderPlacement, OrderQueryService orderQueries,
                                OrderLifecycleService orderLifecycle,
                                RateLimitGuard rateLimitGuard,
                                @Autowired(required = false) VelocityService velocityService) {
        this.orderPlacement = orderPlacement;
        this.orderQueries = orderQueries;
        this.orderLifecycle = orderLifecycle;
        this.rateLimitGuard = rateLimitGuard;
        this.velocityService = velocityService;
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

    @PostMapping("/{orderId}/confirm")
    @Operation(summary = "Confirm a PENDING order and push it to Velocity Shipping Pending AWB queue")
    public ResponseEntity<ApiResponse<OrderResponse>> confirmOrder(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String orderId) {
        rateLimitGuard.enforce("admin-order-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Order confirmed",
                OrderResponse.forAdmin(velocityService.confirmOrder(admin.getId(), orderId))));
    }

    @PostMapping("/{orderId}/shipment/velocity")
    @Operation(summary = "Push order to Velocity Shipping Pending AWB queue")
    public ResponseEntity<ApiResponse<OrderResponse>> pushVelocityShipment(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String orderId) {
        rateLimitGuard.enforce("admin-order-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Order synced to Velocity Shipping Pending AWB queue",
                OrderResponse.forAdmin(velocityService.pushOrderToVelocity(admin.getId(), orderId))));
    }

    @GetMapping("/{orderId}/label")
    @Operation(summary = "Get Velocity shipping label details and automatically mark order PACKED")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> downloadOrderLabel(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String orderId) {
        VelocityService.LabelDownloadResult res = velocityService.getLabelInfoAndMarkPacked(admin.getId(), orderId);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("orderId", res.orderId());
        body.put("orderNumber", res.orderNumber());
        body.put("waybill", res.waybill());
        body.put("labelUrl", res.labelUrl());
        body.put("overallStatus", res.overallStatus());
        body.put("transitionedToPacked", res.transitionedToPacked());
        if (res.pdfBytes() != null && res.pdfBytes().length > 0) {
            body.put("pdfBase64", java.util.Base64.getEncoder().encodeToString(res.pdfBytes()));
        }
        return ResponseEntity.ok(ApiResponse.ok("Shipping label ready", body));
    }

    @PostMapping("/{orderId}/sync-tracking")
    @Operation(summary = "Refresh live tracking & RTO status for Velocity shipment")
    public ResponseEntity<ApiResponse<OrderResponse>> syncVelocityTracking(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String orderId) {
        rateLimitGuard.enforce("admin-order-write", admin.getId(), 30, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Tracking synced from Velocity Shipping",
                OrderResponse.forAdmin(velocityService.syncTrackingForOrder(orderId))));
    }

    @PostMapping("/{orderId}/rto/restock")
    @Operation(summary = "Verify RTO return receipt and restock returned item quantities into inventory")
    public ResponseEntity<ApiResponse<OrderResponse>> restockRtoOrder(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String orderId) {
        rateLimitGuard.enforce("admin-order-write", admin.getId(), 20, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("RTO inventory restocked",
                OrderResponse.forAdmin(velocityService.restockRtoOrder(admin.getId(), orderId))));
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
