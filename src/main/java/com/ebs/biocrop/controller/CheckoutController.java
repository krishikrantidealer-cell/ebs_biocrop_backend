package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.CheckoutConfirmRequest;
import com.ebs.biocrop.dto.request.CheckoutPaymentInitRequest;
import com.ebs.biocrop.dto.request.PlaceOrderRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.CheckoutPaymentInitResponse;
import com.ebs.biocrop.dto.response.CheckoutSummaryResponse;
import com.ebs.biocrop.dto.response.CartResponse;
import com.ebs.biocrop.dto.response.OrderResponse;
import com.ebs.biocrop.dto.response.RazorpayOrderResult;
import com.ebs.biocrop.dto.response.ShiprocketServiceabilityResponse;
import com.ebs.biocrop.entity.*;
import com.ebs.biocrop.entity.enums.PaymentMethod;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.CartRepository;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.*;
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
import java.util.*;

@RestController
@RequestMapping("/api/v1/checkout")
@Tag(name = "Checkout & Payments", description = "Integrated checkout with Razorpay payments and Shiprocket logistics.")
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    private final CartService cartService;
    private final OrderPlacementService orderPlacementService;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final RazorpayService razorpayService;
    private final ShiprocketService shiprocketService;
    private final OrderLifecycleService orderLifecycleService;
    private final RateLimitGuard rateLimitGuard;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public CheckoutController(
            CartService cartService,
            OrderPlacementService orderPlacementService,
            OrderRepository orderRepository,
            UserRepository userRepository,
            RazorpayService razorpayService,
            ShiprocketService shiprocketService,
            OrderLifecycleService orderLifecycleService,
            RateLimitGuard rateLimitGuard,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.cartService = cartService;
        this.orderPlacementService = orderPlacementService;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.razorpayService = razorpayService;
        this.shiprocketService = shiprocketService;
        this.orderLifecycleService = orderLifecycleService;
        this.rateLimitGuard = rateLimitGuard;
        this.objectMapper = objectMapper;
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
        if (!shiprocketService.isConfigured()) {
            throw new AppException("Courier serviceability integration is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }
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
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CheckoutPaymentInitRequest request) {

        if (!razorpayService.isConfigured()) {
            throw new AppException("Payment integration is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }

        rateLimitGuard.enforce("payment-init", customer.getId(), 10, Duration.ofMinutes(1));
        String checkoutKey = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (checkoutKey.isBlank() || checkoutKey.length() > 128) {
            throw new AppException("A valid Idempotency-Key header is required", HttpStatus.BAD_REQUEST);
        }

        String phone = customer.getUsername();
        CartResponse cart = cartService.getCart(phone);
        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new AppException("Your cart is empty. Please add items before checking out.", HttpStatus.BAD_REQUEST);
        }

        PlaceOrderRequest orderRequest = request.getOrderRequest();
        if (orderRequest == null) {
            throw new AppException("Order details are required", HttpStatus.BAD_REQUEST);
        }
        if (orderRequest.getPaymentMethod() == PaymentMethod.COD) {
            throw new AppException("COD orders are placed after customer confirmation without online payment", HttpStatus.BAD_REQUEST);
        }
        double totalAmount = cart.getFinalAmount() != null ? cart.getFinalAmount() : (cart.getTotalAmount() != null ? cart.getTotalAmount() : 0.0);
        BigDecimal amount = orderPlacementService.calculateOnlinePaymentAmount(customer.getId(), orderRequest);
        double amountToPay = amount.doubleValue();
        double advancePct = orderRequest.getPaymentMethod() == PaymentMethod.PREPAID ? 100.0
                : (orderRequest.getAdvancePercentage() == null ? 10.0 : orderRequest.getAdvancePercentage().doubleValue());

        String receipt = "rcpt_" + System.currentTimeMillis();
        Map<String, String> notes = Map.of(
                "customerId", customer.getId(),
                "customerPhone", phone,
                "idempotencyKey", checkoutKey,
                "paymentMethod", orderRequest.getPaymentMethod().name(),
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
     * Confirms the payment signature and records the advance against one unified checkout order.
     */
    @PostMapping("/confirm-order")
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Verify payment signature and confirm one unified checkout order")
    public ResponseEntity<ApiResponse<OrderResponse>> confirmOrder(
            @AuthenticationPrincipal CustomUserDetails customer,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CheckoutConfirmRequest confirmRequest) {

        if (!razorpayService.isConfigured()) {
            throw new AppException("Payment integration is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }

        rateLimitGuard.enforce("order-confirm", customer.getId(), 5, Duration.ofMinutes(1));

        String rzpOrderId = confirmRequest.getRazorpayOrderId();
        String rzpPaymentId = confirmRequest.getRazorpayPaymentId();
        String rzpSignature = confirmRequest.getRazorpaySignature();

        // 1. Verify Razorpay cryptographic signature
        if (rzpOrderId == null || rzpOrderId.isBlank() || rzpPaymentId == null || rzpPaymentId.isBlank()
                || rzpSignature == null || rzpSignature.isBlank()) {
            throw new AppException("Razorpay order, payment, and signature are required", HttpStatus.BAD_REQUEST);
        }
        boolean validSignature = razorpayService.verifyPaymentSignature(rzpOrderId, rzpPaymentId, rzpSignature);
        if (!validSignature) {
            log.error("Invalid Razorpay payment signature for order [{}] payment [{}]", rzpOrderId, rzpPaymentId);
            throw new AppException("Razorpay payment signature verification failed. Order not confirmed.", HttpStatus.PAYMENT_REQUIRED);
        }

        RazorpayService.PaymentDetails paymentDetails = razorpayService.fetchPaymentDetails(rzpPaymentId);
        RazorpayService.OrderDetails providerOrder = razorpayService.fetchOrderDetails(rzpOrderId);
        if (!Objects.equals(rzpPaymentId, paymentDetails.paymentId())
                || !Objects.equals(rzpOrderId, paymentDetails.orderId())
                || !Objects.equals(rzpOrderId, providerOrder.orderId())
                || !paymentDetails.captured()
                || !"captured".equalsIgnoreCase(paymentDetails.status())
                || !"INR".equalsIgnoreCase(paymentDetails.currency())
                || !"INR".equalsIgnoreCase(providerOrder.currency())
                || !Objects.equals(customer.getId(), providerOrder.notes().get("customerId"))
                || !Objects.equals(idempotencyKey.trim(), providerOrder.notes().get("idempotencyKey"))) {
            throw new AppException("Payment is not captured or does not match this checkout", HttpStatus.PAYMENT_REQUIRED);
        }
        Optional<Order> existingOrder = orderRepository.findByCustomerIdAndIdempotencyKey(customer.getId(), idempotencyKey.trim());
        if (existingOrder.isPresent()) {
            Order existing = existingOrder.get();
            boolean sameCapturedPayment = existing.getPayment() != null
                    && Objects.equals(existing.getPayment().getProviderOrderId(), rzpOrderId)
                    && existing.getPayment().getTransactions() != null
                    && existing.getPayment().getTransactions().stream().anyMatch(tx -> Objects.equals(tx.getProviderPaymentId(), rzpPaymentId));
            if (!sameCapturedPayment) throw new AppException("Idempotency-Key was already used for a different payment", HttpStatus.CONFLICT);
            return ResponseEntity.ok(ApiResponse.ok("Checkout payment was already confirmed", OrderResponse.forCustomer(existing)));
        }
        BigDecimal expectedAmount = orderPlacementService.calculateOnlinePaymentAmount(customer.getId(), confirmRequest.getOrderRequest());
        long expectedPaise = expectedAmount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (paymentDetails.amountPaise() != expectedPaise || providerOrder.amountPaise() != expectedPaise) {
            throw new AppException("Captured amount does not match the current checkout total", HttpStatus.CONFLICT);
        }

        Order order = orderPlacementService.createOrders(customer.getId(), confirmRequest.getOrderRequest(), idempotencyKey);
        order.getPayment().setProvider("RAZORPAY");
        order.getPayment().setProviderOrderId(rzpOrderId);
        order = orderRepository.save(order);
        Order confirmed = orderLifecycleService.recordAdvancePaymentCaptured(order.getId(), rzpPaymentId,
                BigDecimal.valueOf(paymentDetails.amountPaise(), 2));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Checkout order payment confirmed",
                OrderResponse.forCustomer(confirmed)));
    }

    /**
     * Get embedded shipment status for the authenticated customer's order.
     */
    @GetMapping("/track/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get live Shiprocket tracking details for an order")
    public ResponseEntity<ApiResponse<Map<String, Object>>> trackOrder(
            @AuthenticationPrincipal CustomUserDetails customer,
            @PathVariable String orderId) {

        Order order = orderRepository.findByIdAndCustomerId(orderId, customer.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId));

        Map<String, Object> tracking = new LinkedHashMap<>();
        tracking.put("orderNumber", order.getOrderNumber());
        tracking.put("overallStatus", order.getOverallStatus());
        List<Map<String, Object>> fulfillmentTracking = new ArrayList<>();
        if (order.getSellerFulfillments() != null) {
            for (Order.SellerFulfillment fulfillment : order.getSellerFulfillments()) {
                for (Order.Shipment shipment : fulfillment.getShipments()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sellerFulfillmentId", fulfillment.getId());
                    item.put("shipmentId", shipment.getShipmentId());
                    item.put("carrier", shipment.getCarrier());
                    item.put("trackingNumber", shipment.getTrackingNumber());
                    item.put("status", shipment.getStatus());
                    item.put("lastTrackingUpdateAt", shipment.getLastTrackingUpdateAt());
                    item.put("lastTrackingDescription", shipment.getLastTrackingDescription());
                    fulfillmentTracking.add(item);
                }
            }
        }
        tracking.put("shipments", fulfillmentTracking);

        return ResponseEntity.ok(ApiResponse.ok("Tracking details retrieved", tracking));
    }

    /**
     * Webhook listener for asynchronous Razorpay payment and refund lifecycle events.
     */
    @PostMapping(value = "/webhook", consumes = "application/json")
    @Operation(summary = "Receive and process asynchronous Razorpay payment webhooks")
    public ResponseEntity<Map<String, Object>> handleRazorpayWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        if (signature == null || signature.isBlank()) {
            log.warn("Razorpay webhook received without X-Razorpay-Signature header");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "error", "message", "Missing X-Razorpay-Signature"));
        }

        boolean isValid = razorpayService.verifyWebhookSignature(rawPayload, signature);
        if (!isValid) {
            log.error("Razorpay webhook signature verification failed");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "error", "message", "Invalid webhook signature"));
        }

        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(rawPayload);
            String event = root.path("event").asText("");
            log.info("Received valid Razorpay webhook event [{}]", event);

            com.fasterxml.jackson.databind.JsonNode payload = root.path("payload");
            com.fasterxml.jackson.databind.JsonNode paymentEntity = payload.path("payment").path("entity");
            String razorpayPaymentId = paymentEntity.path("id").asText(null);
            String razorpayOrderId = paymentEntity.path("order_id").asText(null);
            long amountPaise = paymentEntity.path("amount").asLong(0);

            switch (event) {
                case "payment.captured", "order.paid" -> {
                    if (razorpayOrderId != null && !razorpayOrderId.isBlank()) {
                        Optional<Order> orderOpt = orderRepository.findByPaymentProviderOrderId(razorpayOrderId);
                        if (orderOpt.isPresent()) {
                            Order order = orderOpt.get();
                            boolean alreadyCaptured = order.getPayment() != null
                                    && com.ebs.biocrop.entity.enums.PaymentStatus.CAPTURED.name().equals(order.getPayment().getStatus())
                                    && order.getPayment().getTransactions() != null
                                    && order.getPayment().getTransactions().stream()
                                            .anyMatch(tx -> Objects.equals(tx.getProviderPaymentId(), razorpayPaymentId));
                            if (!alreadyCaptured) {
                                BigDecimal capturedAmount = BigDecimal.valueOf(amountPaise, 2);
                                orderLifecycleService.recordAdvancePaymentCaptured(order.getId(), razorpayPaymentId, capturedAmount);
                                log.info("Webhook successfully reconciled captured payment [{}] for order [{}]", razorpayPaymentId, order.getId());
                            } else {
                                log.info("Webhook duplicate/replay for already captured payment [{}]", razorpayPaymentId);
                            }
                        } else {
                            log.info("Webhook payment.captured received for provider order [{}] before local order was confirmed", razorpayOrderId);
                        }
                    }
                }
                case "payment.authorized" -> {
                    if (razorpayOrderId != null && !razorpayOrderId.isBlank()) {
                        orderRepository.findByPaymentProviderOrderId(razorpayOrderId).ifPresent(order -> {
                            if (com.ebs.biocrop.entity.enums.PaymentStatus.CREATED.name().equals(order.getPayment().getStatus())) {
                                orderLifecycleService.recordPaymentAuthorized(order.getId(), razorpayPaymentId);
                                log.info("Webhook recorded payment authorization for order [{}]", order.getId());
                            }
                        });
                    }
                }
                case "payment.failed" -> {
                    if (razorpayOrderId != null && !razorpayOrderId.isBlank()) {
                        String errorDesc = paymentEntity.path("error_description").asText("Payment failed");
                        orderRepository.findByPaymentProviderOrderId(razorpayOrderId).ifPresent(order -> {
                            orderLifecycleService.recordPaymentFailed(order.getId(), razorpayPaymentId, errorDesc);
                            log.warn("Webhook recorded payment failure for order [{}]: {}", order.getId(), errorDesc);
                        });
                    }
                }
                case "refund.processed", "refund.created" -> {
                    com.fasterxml.jackson.databind.JsonNode refundEntity = payload.path("refund").path("entity");
                    String refundId = refundEntity.path("id").asText(null);
                    long refundPaise = refundEntity.path("amount").asLong(0);
                    BigDecimal refundAmount = BigDecimal.valueOf(refundPaise, 2);

                    if (razorpayOrderId != null && !razorpayOrderId.isBlank()) {
                        orderRepository.findByPaymentProviderOrderId(razorpayOrderId).ifPresent(order -> {
                            orderLifecycleService.recordPaymentRefunded(order.getId(), refundId, refundAmount, "Processed via Razorpay webhook");
                            log.info("Webhook recorded refund [{}] of amount [{}] for order [{}]", refundId, refundAmount, order.getId());
                        });
                    }
                }
                default -> log.debug("Unhandled Razorpay webhook event [{}]", event);
            }

            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            log.error("Error processing Razorpay webhook payload: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("status", "error", "message", "Internal processing note logged"));
        }
    }
}
