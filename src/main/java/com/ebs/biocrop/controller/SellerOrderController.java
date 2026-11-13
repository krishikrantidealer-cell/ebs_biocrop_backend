package com.ebs.biocrop.controller;

import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.dto.response.OrderResponse;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.OrderQueryService;
import com.ebs.biocrop.service.OrderLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/seller/orders")
@PreAuthorize("hasRole('SELLER')")
@Tag(name = "Seller orders", description = "Orders containing items belonging to the authenticated seller.")
@SecurityRequirement(name = "bearerAuth")
public class SellerOrderController {
    private final OrderQueryService orderQueries;
    private final OrderLifecycleService orderLifecycle;

    public SellerOrderController(OrderQueryService orderQueries, OrderLifecycleService orderLifecycle) {
        this.orderQueries = orderQueries;
        this.orderLifecycle = orderLifecycle;
    }

    // List the authenticated seller's orders.
    @GetMapping
    @Operation(summary = "List the authenticated seller's orders")
    public ResponseEntity<ApiResponse<PagedResponse<OrderResponse>>> getSellerOrders(
            @AuthenticationPrincipal CustomUserDetails seller,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int resolvedPage = PageRequestSupport.resolvePageNumber(page);
        int resolvedSize = PageRequestSupport.resolvePageSize(page, size);
        Page<Order> result = orderQueries.listForSeller(seller.getId(), resolvedPage, resolvedSize);
        return ResponseEntity.ok(ApiResponse.ok("Seller orders retrieved",
                PagedResponse.from(result, order -> OrderResponse.forSeller(order, seller.getId()))));
    }

    // Get one order belonging to the authenticated seller.
    @GetMapping("/{orderId}")
    @Operation(summary = "Get one order belonging to the authenticated seller")
    public ResponseEntity<ApiResponse<OrderResponse>> getSellerOrderById(
            @AuthenticationPrincipal CustomUserDetails seller,
            @PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.ok("Order retrieved",
                OrderResponse.forSeller(orderQueries.getForSeller(seller.getId(), orderId), seller.getId())));
    }

    @PostMapping("/{orderId}/fulfillments/{fulfillmentId}/decision")
    @Operation(summary = "Accept or reject this seller's fulfillment within its 12-hour response window")
    public ResponseEntity<ApiResponse<OrderResponse>> decideSellerFulfillment(
            @AuthenticationPrincipal CustomUserDetails seller,
            @PathVariable String orderId,
            @PathVariable String fulfillmentId,
            @RequestParam String decision) {
        Order updated = orderLifecycle.respondToSellerFulfillment(
                seller.getId(), orderId, fulfillmentId, decision);
        return ResponseEntity.ok(ApiResponse.ok("Seller fulfillment decision recorded",
                OrderResponse.forSeller(updated, seller.getId())));
    }

    @PostMapping("/{orderId}/fulfillments/{fulfillmentId}/packed")
    @Operation(summary = "Mark an accepted seller fulfillment packed before its 48-hour dispatch deadline")
    public ResponseEntity<ApiResponse<OrderResponse>> markSellerFulfillmentPacked(
            @AuthenticationPrincipal CustomUserDetails seller,
            @PathVariable String orderId,
            @PathVariable String fulfillmentId) {
        Order updated = orderLifecycle.markSellerFulfillmentPacked(seller.getId(), orderId, fulfillmentId);
        return ResponseEntity.ok(ApiResponse.ok("Seller fulfillment marked packed; carrier handover remains pending",
                OrderResponse.forSeller(updated, seller.getId())));
    }

    @PostMapping("/{orderId}/fulfillments/{fulfillmentId}/shipment")
    @Operation(summary = "Book Shiprocket courier shipment and generate AWB label for this seller's fulfillment")
    public ResponseEntity<ApiResponse<OrderResponse>> bookFulfillmentShipment(
            @AuthenticationPrincipal CustomUserDetails seller,
            @PathVariable String orderId,
            @PathVariable String fulfillmentId) {
        Order updated = orderLifecycle.bookSellerFulfillmentShipment(seller.getId(), orderId, fulfillmentId);
        return ResponseEntity.ok(ApiResponse.ok("Shipment booked successfully in Shiprocket",
                OrderResponse.forSeller(updated, seller.getId())));
    }

    @PostMapping("/{orderId}/fulfillments/{fulfillmentId}/handover")
    @Operation(summary = "Confirm carrier pickup handover for a booked fulfillment shipment")
    public ResponseEntity<ApiResponse<OrderResponse>> confirmCarrierHandover(
            @AuthenticationPrincipal CustomUserDetails seller,
            @PathVariable String orderId,
            @PathVariable String fulfillmentId,
            @RequestParam(required = false) String trackingNumber) {
        Order updated = orderLifecycle.recordCarrierHandover(orderId, fulfillmentId, trackingNumber, java.time.LocalDateTime.now());
        return ResponseEntity.ok(ApiResponse.ok("Carrier handover confirmed; fulfillment dispatched",
                OrderResponse.forSeller(updated, seller.getId())));
    }

}
