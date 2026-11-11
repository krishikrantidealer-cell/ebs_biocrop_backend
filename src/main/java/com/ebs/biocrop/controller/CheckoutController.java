package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.CheckoutConfirmRequest;
import com.ebs.biocrop.dto.request.CheckoutPaymentInitRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.CheckoutPaymentInitResponse;
import com.ebs.biocrop.dto.response.CheckoutSummaryResponse;
import com.ebs.biocrop.dto.response.CartResponse;
import com.ebs.biocrop.dto.response.OrderResponse;
import com.ebs.biocrop.dto.response.PlacedOrdersResponse;
import com.ebs.biocrop.dto.response.RazorpayOrderResult;
import com.ebs.biocrop.dto.response.ShiprocketOrderResult;
import com.ebs.biocrop.dto.response.ShiprocketServiceabilityResponse;
import com.ebs.biocrop.entity.*;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CartRepository;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.*;
import com.ebs.biocrop.websocket.WebSocketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/checkout")
@Tag(name = "Checkout & Payments", description = "Integrated checkout with Razorpay payments and Shiprocket logistics.")
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    private final CartService cartService;
    private final CartRepository cartRepository;
    private final OrderPlacementService orderPlacementService;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final RazorpayService razorpayService;
    private final ShiprocketService shiprocketService;
    private final WebSocketService webSocketService;
    private final RateLimitGuard rateLimitGuard;

    public CheckoutController(
            CartService cartService,
            CartRepository cartRepository,
            OrderPlacementService orderPlacementService,
            OrderRepository orderRepository,
            UserRepository userRepository,
            RazorpayService razorpayService,
            ShiprocketService shiprocketService,
            WebSocketService webSocketService,
            RateLimitGuard rateLimitGuard) {
        this.cartService = cartService;
        this.cartRepository = cartRepository;
        this.orderPlacementService = orderPlacementService;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.razorpayService = razorpayService;
        this.shiprocketService = shiprocketService;
        this.webSocketService = webSocketService;
        this.rateLimitGuard = rateLimitGuard;
    }

    /**
     * Check postal pincode delivery availability and shipping rates via Shiprocket.
     */
    @GetMapping("/serviceability")
    @Operation(summary = "Check pincode serviceability and estimated delivery via Shiprocket")
    public ResponseEntity<ApiResponse<ShiprocketServiceabilityResponse>> checkServiceability(
            @RequestParam String pincode,
            @RequestParam(defaultValue = "0.5") double weight,
            @RequestParam(defaultValue = "false") boolean cod) {
        ShiprocketServiceabilityResponse response = shiprocketService.checkServiceability(pincode, weight, cod);
        return ResponseEntity.ok(ApiResponse.ok("Serviceability checked", response));
    }

    /**
     * Returns the customer's checkout summary, verifying cart items and total amounts.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get checkout summary for authenticated customer")
    public ResponseEntity<ApiResponse<CheckoutSummaryResponse>> getCheckoutSummary(
            @AuthenticationPrincipal CustomUserDetails customer) {
        String phone = customer.getUsername();
        CartResponse cart = cartService.getCart(phone);

        double subtotal = cart.getTotalAmount() != null ? cart.getTotalAmount() : 0.0;
        double discount = cart.getDiscountAmount() != null ? cart.getDiscountAmount() : 0.0;
        double finalAmount = cart.getFinalAmount() != null ? cart.getFinalAmount() : subtotal;
        double shipping = finalAmount >= 500.0 || finalAmount == 0.0 ? 0.0 : 50.0;
        double finalPayable = Math.max(0.0, finalAmount + shipping);

        List<String> errors = new ArrayList<>();
        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            errors.add("Your cart is currently empty.");
        }

        CheckoutSummaryResponse summary = new CheckoutSummaryResponse(
                cart,
                errors.isEmpty(),
                errors,
                null,
                subtotal,
                discount,
                shipping,
                finalPayable
        );

        return ResponseEntity.ok(ApiResponse.ok("Checkout summary retrieved", summary));
    }

    /**
     * Initiates a Razorpay payment order for the customer's cart.
     */
    @PostMapping("/initiate-payment")
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a Razorpay payment order for the current checkout")
    public ResponseEntity<ApiResponse<CheckoutPaymentInitResponse>> initiatePayment(
            @AuthenticationPrincipal CustomUserDetails customer,
            @Valid @RequestBody CheckoutPaymentInitRequest request) {

        rateLimitGuard.enforce("payment-init", customer.getId(), 10, Duration.ofMinutes(1));

        String phone = customer.getUsername();
        CartResponse cart = cartService.getCart(phone);
        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new AppException("Your cart is empty. Please add items before checking out.", HttpStatus.BAD_REQUEST);
        }

        double totalAmount = cart.getFinalAmount() != null ? cart.getFinalAmount() : (cart.getTotalAmount() != null ? cart.getTotalAmount() : 0.0);
        double advancePct = 100.0;

        if (request.getPaymentMethod() == OrderPaymentMethod.PARTIAL) {
            BigDecimal requestedAdv = request.getAdvancePercentage();
            if (requestedAdv == null || !List.of(10, 20, 50).contains(requestedAdv.intValue())) {
                throw new AppException("Partial payment options must be 10%, 20%, or 50%.", HttpStatus.BAD_REQUEST);
            }
            advancePct = requestedAdv.doubleValue();
        }

        double amountToPay = BigDecimal.valueOf(totalAmount)
                .multiply(BigDecimal.valueOf(advancePct))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .doubleValue();

        String receipt = "rcpt_" + System.currentTimeMillis();
        Map<String, String> notes = Map.of(
                "customerId", customer.getId(),
                "customerPhone", phone,
                "paymentMethod", request.getPaymentMethod().name(),
                "advancePercentage", String.valueOf(advancePct)
        );

        RazorpayOrderResult rzpOrder = razorpayService.createOrder(amountToPay, receipt, "INR", notes);

        User user = userRepository.findById(customer.getId()).orElse(null);
        String customerName = "Farmer Customer";
        if (user != null) {
            if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
                customerName = user.getFirstName() + (user.getLastName() != null ? " " + user.getLastName() : "");
            }
        }
        String customerEmail = user != null && user.getEmail() != null ? user.getEmail() : "";

        CheckoutPaymentInitResponse response = new CheckoutPaymentInitResponse(
                rzpOrder.orderId(),
                razorpayService.getKeyId(),
                rzpOrder.amountPaise(),
                amountToPay,
                rzpOrder.currency(),
                totalAmount,
                advancePct,
                customerName,
                phone,
                customerEmail
        );

        return ResponseEntity.ok(ApiResponse.ok("Razorpay payment initialized", response));
    }

    /**
     * Confirms the payment signature, converts the cart into confirmed orders, and dispatches to Shiprocket.
     */
    @PostMapping("/confirm-order")
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Verify Razorpay signature, finalize order placement, and dispatch via Shiprocket")
    public ResponseEntity<ApiResponse<PlacedOrdersResponse>> confirmOrder(
            @AuthenticationPrincipal CustomUserDetails customer,
            @Valid @RequestBody CheckoutConfirmRequest confirmRequest) {

        rateLimitGuard.enforce("order-confirm", customer.getId(), 5, Duration.ofMinutes(1));

        String rzpOrderId = confirmRequest.getRazorpayOrderId();
        String rzpPaymentId = confirmRequest.getRazorpayPaymentId();
        String rzpSignature = confirmRequest.getRazorpaySignature();

        // 1. Verify Razorpay cryptographic signature
        if (rzpOrderId != null && !rzpOrderId.isBlank()) {
            boolean validSignature = razorpayService.verifyPaymentSignature(rzpOrderId, rzpPaymentId, rzpSignature);
            if (!validSignature) {
                log.error("Invalid Razorpay payment signature for order [{}] payment [{}]", rzpOrderId, rzpPaymentId);
                throw new AppException("Razorpay payment signature verification failed. Order not confirmed.", HttpStatus.PAYMENT_REQUIRED);
            }
        }

        // 2. Place orders via core OrderPlacementService
        PlacedOrdersResponse placedOrders = orderPlacementService.createOrders(customer.getId(), confirmRequest.getOrderRequest());

        // 3. Attach Razorpay payment confirmation and trigger Shiprocket fulfillment
        List<OrderResponse> updatedResponses = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (OrderResponse orderResp : placedOrders.orders()) {
            Order order = orderRepository.findById(orderResp.id()).orElse(null);
            if (order != null) {
                // Record Razorpay Details
                order.setRazorpayOrderId(rzpOrderId);
                order.setRazorpayPaymentId(rzpPaymentId);
                order.setRazorpaySignature(rzpSignature);

                double amountPaid = order.getAdvanceAmountDue() != null ? order.getAdvanceAmountDue() : order.getTotalAmount();
                if (order.getPaymentMethod() == OrderPaymentMethod.PREPAID) {
                    amountPaid = order.getTotalAmount();
                }

                OrderPaymentTransaction tx = new OrderPaymentTransaction();
                tx.setAmount(amountPaid);
                tx.setStatus("SUCCESS");
                tx.setProvider("RAZORPAY");
                tx.setProviderPaymentId(rzpPaymentId != null ? rzpPaymentId : ("pay_mock_" + UUID.randomUUID().toString().substring(0, 8)));
                tx.setReferenceNumber(rzpOrderId != null ? rzpOrderId : order.getOrderNumber());
                tx.setPaidAt(now);

                if (order.getPayment() == null) {
                    order.setPayment(new OrderPaymentDetails());
                }
                order.getPayment().getTransactions().add(tx);
                order.recordConfirmedPayment(amountPaid);

                // Create Shiprocket Fulfillment Order & Shipment
                try {
                    ShiprocketOrderResult shipResult = shiprocketService.createOrderAndShipment(order);
                    if (shipResult != null) {
                        order.setShiprocketOrderId(shipResult.shiprocketOrderId());
                        order.setShiprocketShipmentId(shipResult.shipmentId());
                        order.setShiprocketAwbCode(shipResult.awbCode());
                        order.setShiprocketCourierName(shipResult.courierName());
                        order.setShiprocketTrackingUrl(shipResult.trackingUrl());
                        order.setShiprocketStatus(shipResult.status());

                        OrderStatusEvent shipEvent = new OrderStatusEvent();
                        shipEvent.setStatus(OrderStatus.PACKED.name());
                        shipEvent.setChangedAt(now);
                        shipEvent.setChangedBy("SYSTEM_SHIPROCKET");
                        shipEvent.setNote("Dispatched to Shiprocket: " + shipResult.courierName() + " (AWB: " + shipResult.awbCode() + ")");
                        order.getStatusHistory().add(shipEvent);
                    }
                } catch (Exception e) {
                    log.error("Failed to automatically dispatch order [{}] to Shiprocket: {}", order.getOrderNumber(), e.getMessage());
                }

                order.setUpdatedAt(now);
                order = orderRepository.save(order);
                webSocketService.broadcast("orders:status", order);

                updatedResponses.add(OrderResponse.forCustomer(order));
            } else {
                updatedResponses.add(orderResp);
            }
        }

        PlacedOrdersResponse finalResponse = new PlacedOrdersResponse(placedOrders.checkoutGroupId(), updatedResponses);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Order placed and payment confirmed", finalResponse));
    }

    /**
     * Get live Shiprocket tracking status for an order.
     */
    @GetMapping("/track/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get live Shiprocket tracking details for an order")
    public ResponseEntity<ApiResponse<Map<String, Object>>> trackOrder(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String orderId) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));

        if (!customer.getId().equals(order.getCustomerId())) {
            throw new AppException("You do not have access to this order's tracking", HttpStatus.FORBIDDEN);
        }

        Map<String, Object> tracking = new LinkedHashMap<>();
        tracking.put("orderNumber", order.getOrderNumber());
        tracking.put("orderStatus", order.getOrderStatus());
        tracking.put("courierName", order.getShiprocketCourierName());
        tracking.put("awbCode", order.getShiprocketAwbCode());
        tracking.put("trackingUrl", order.getShiprocketTrackingUrl());
        tracking.put("status", order.getShiprocketStatus());

        if (order.getShiprocketAwbCode() != null && !order.getShiprocketAwbCode().isBlank()) {
            String rawShiprocketJson = shiprocketService.trackShipment(order.getShiprocketAwbCode());
            tracking.put("rawTracking", rawShiprocketJson);
        }

        return ResponseEntity.ok(ApiResponse.ok("Tracking details retrieved", tracking));
    }
}
