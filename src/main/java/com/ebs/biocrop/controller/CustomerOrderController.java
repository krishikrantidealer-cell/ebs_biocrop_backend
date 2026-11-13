package com.ebs.biocrop.controller;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.dto.request.PlaceOrderRequest;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

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

    // Create one checkout order containing every seller's items.
    @PostMapping
    @Operation(summary = "Create one checkout order from the authenticated customer's cart")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrders(
            @AuthenticationPrincipal CustomUserDetails customer,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        rateLimitGuard.enforce("order-create-customer", customer.getId(), 5, Duration.ofMinutes(1));
        if (request.getPaymentMethod() != com.ebs.biocrop.entity.enums.PaymentMethod.COD) {
            throw new com.ebs.biocrop.exception.AppException(
                    "Online checkout must be completed through /api/v1/checkout before an order is created",
                    HttpStatus.CONFLICT);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("COD order created; payment is due on delivery",
                OrderResponse.forCustomer(orderPlacement.createOrders(customer.getId(), request, idempotencyKey))));
    }

    // List the authenticated customer's orders
    @GetMapping
    @Operation(summary = "List the authenticated customer's orders")
    public ResponseEntity<ApiResponse<PagedResponse<OrderResponse>>> getCustomerOrders(
            @AuthenticationPrincipal CustomUserDetails customer,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int resolvedPage = PageRequestSupport.resolvePageNumber(page);
        int resolvedSize = PageRequestSupport.resolvePageSize(page, size);
        Page<Order> result = orderQueries.listForCustomer(customer.getId(), resolvedPage, resolvedSize);
        return ResponseEntity.ok(ApiResponse.ok("Customer orders retrieved",
                PagedResponse.from(result, OrderResponse::forCustomer)));
    }

    // Get one of the authenticated customer's orders
    @GetMapping("/{orderId}")
    @Operation(summary = "Get one of the authenticated customer's orders")
    public ResponseEntity<ApiResponse<OrderResponse>> getCustomerOrderById(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.ok("Order retrieved",
                OrderResponse.forCustomer(orderQueries.getForCustomer(customer.getId(), orderId))));
    }

    // Read the one order document for a checkout by its public correlation identifier.
    @GetMapping("/checkout/{checkoutGroupId}")
    @Operation(summary = "Get the authenticated customer's single order by checkout ID")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrdersByCheckoutGroup(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String checkoutGroupId) {
        return ResponseEntity.ok(ApiResponse.ok("Checkout order retrieved",
                OrderResponse.forCustomer(orderQueries.getCheckoutGroupForCustomer(customer.getId(), checkoutGroupId))));
    }

    // Cancel a pending order owned by the authenticated customer
    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel a pending order owned by the authenticated customer")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelCustomerOrder(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String orderId) {
        rateLimitGuard.enforce("order-cancel", customer.getId(), 10, Duration.ofMinutes(1));
        return ResponseEntity.ok(ApiResponse.ok("Order cancelled",
                OrderResponse.forCustomer(orderLifecycle.cancelForCustomer(customer.getId(), orderId))));
    }

    @PostMapping("/{orderId}/decision-windows/{decisionWindowId}")
    @Operation(summary = "Resolve a seller rejection or timeout decision window")
    public ResponseEntity<ApiResponse<OrderResponse>> resolveDecisionWindow(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String orderId,
            @PathVariable String decisionWindowId,
            @RequestParam String decision,
            @RequestParam(required = false) List<String> itemOrderIds) {
        return ResponseEntity.ok(ApiResponse.ok("Customer order decision recorded",
                OrderResponse.forCustomer(orderLifecycle.resolveCustomerDecisionWindow(
                        customer.getId(), orderId, decisionWindowId, decision, itemOrderIds))));
    }
}
