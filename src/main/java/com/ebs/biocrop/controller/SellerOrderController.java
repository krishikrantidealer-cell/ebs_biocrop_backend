package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.dto.response.OrderResponse;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.OrderQueryService;
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
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/seller/orders")
@PreAuthorize("hasRole('SELLER')")
@Tag(name = "Seller orders", description = "Orders containing items belonging to the authenticated seller.")
@SecurityRequirement(name = "bearerAuth")
public class SellerOrderController {
    private final OrderQueryService orderQueries;

    public SellerOrderController(OrderQueryService orderQueries) {
        this.orderQueries = orderQueries;
    }

    // List the authenticated seller's orders.
    @GetMapping
    @Operation(summary = "List the authenticated seller's orders")
    public ResponseEntity<ApiResponse<PagedResponse<OrderResponse>>> getSellerOrders(
            @AuthenticationPrincipal CustomUserDetails seller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Order> result = orderQueries.listForSeller(seller.getId(), page, size);
        return ResponseEntity.ok(ApiResponse.ok("Seller orders retrieved",
                PagedResponse.from(result, OrderResponse::forSeller)));
    }

    // Get one order belonging to the authenticated seller.
    @GetMapping("/{orderId}")
    @Operation(summary = "Get one order belonging to the authenticated seller")
    public ResponseEntity<ApiResponse<OrderResponse>> getSellerOrderById(
            @AuthenticationPrincipal CustomUserDetails seller,
            @PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.ok("Order retrieved",
                OrderResponse.forSeller(orderQueries.getForSeller(seller.getId(), orderId))));
    }

}
