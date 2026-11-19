package com.ebs.biocrop;

import com.ebs.biocrop.controller.AdminOrderController;
import com.ebs.biocrop.controller.DelhiveryWebhookController;
import com.ebs.biocrop.controller.VariantLabelController;
import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.OrderAddressSnapshot;
import com.ebs.biocrop.entity.OrderItem;
import com.ebs.biocrop.entity.OrderPaymentDetails;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.entity.VariantLabel;
import com.ebs.biocrop.entity.enums.OrderStatus;
import com.ebs.biocrop.entity.enums.UserRole;
import org.springframework.mock.env.MockEnvironment;
import com.ebs.biocrop.exception.GlobalExceptionHandler;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.repository.VariantLabelRepository;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.DelhiveryOneService;
import com.ebs.biocrop.service.OrderLifecycleService;
import com.ebs.biocrop.service.OrderPlacementService;
import com.ebs.biocrop.service.OrderQueryService;
import com.ebs.biocrop.service.RateLimitGuard;
import com.ebs.biocrop.service.RedisOtpCooldown;
import com.ebs.biocrop.service.RedisOtpStore;
import com.ebs.biocrop.service.SensitiveIdentifierHasher;
import com.ebs.biocrop.service.impl.FastrrCheckoutServiceImpl;
import com.ebs.biocrop.websocket.WebSocketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprehensive End-to-End Verification Suite covering:
 * 1. Multi-seller Fastrr order splitting (4 products across 2 sellers -> 2 separate PENDING orders) + duplicate idempotency
 * 2. Full Delhivery One real-HTTP lifecycle (PENDING -> CONFIRMED -> AWB + Label -> Download -> PACKED -> SHIPPED -> IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED)
 * 3. Negative guards (404 on label before booking, 409 on booking while PENDING, 401 on bad webhook token, terminal state regression guard)
 * 4. Full RTO lifecycle + manual Verify RTO & Restock idempotency (no auto-restock, single increment only)
 * 5. Delhivery fallback sync-tracking over real HTTP
 * 6. Variant label higher-unit conversion (g -> kg, ml -> Ltr)
 * 7. OTP Redis in-memory fallback when Redis is unavailable
 */
@ExtendWith(MockitoExtension.class)
class OrderFulfillmentEndToEndTest {

    @Mock private OrderRepository orderRepository;
    @Mock private VariantLabelRepository variantLabelRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private WebSocketService webSocketService;
    @Mock private OrderPlacementService orderPlacementService;
    @Mock private OrderQueryService orderQueryService;
    @Mock private OrderLifecycleService orderLifecycleService;
    @Mock private RateLimitGuard rateLimitGuard;
    @Mock private StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Map<String, Order> orderStore = new ConcurrentHashMap<>();
    private final Map<String, Product> productStore = new ConcurrentHashMap<>();

    private HttpServer delhiveryStubServer;
    private String delhiveryBaseUrl;
    private DelhiveryOneService delhiveryOneService;
    private MockMvc mockMvc;

    private static final String ADMIN_ID = "admin-e2e-001";
    private static final String WEBHOOK_TOKEN = "delhivery-e2e-secret";
    private static final String SAMPLE_AWB = "DLV9988776655";
    private static final byte[] SAMPLE_PDF = "%PDF-1.4\n%Delhivery-Test-Label\n%%EOF".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws IOException {
        orderStore.clear();
        productStore.clear();

        // Start a real local HTTP server simulating Delhivery One APIs
        delhiveryStubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = delhiveryStubServer.getAddress().getPort();
        delhiveryBaseUrl = "http://127.0.0.1:" + port;

        // 1. Delhivery CMU create shipment endpoint
        delhiveryStubServer.createContext("/api/cmu/create.json", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Token test-delhivery-token".equals(auth)) {
                sendJson(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            sendJson(exchange, 200, "{\"success\":true,\"packages\":[{\"waybill\":\"" + SAMPLE_AWB + "\",\"status\":\"Success\"}]}");
        });

        // 2. Delhivery packing slip metadata endpoint
        delhiveryStubServer.createContext("/api/p/packing_slip", exchange -> {
            String pdfLink = delhiveryBaseUrl + "/labels/" + SAMPLE_AWB + ".pdf";
            sendJson(exchange, 200, "{\"packages\":[{\"wbn\":\"" + SAMPLE_AWB + "\",\"pdf_download_link\":\"" + pdfLink + "\"}]}");
        });

        // 3. Delhivery label PDF download endpoint
        delhiveryStubServer.createContext("/labels/" + SAMPLE_AWB + ".pdf", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, SAMPLE_PDF.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(SAMPLE_PDF);
            }
        });

        // 4. Delhivery tracking pull endpoint (fallback sync)
        delhiveryStubServer.createContext("/api/v1/packages/json/", exchange -> {
            String body = """
                    {
                      "ShipmentData": [
                        {
                          "Shipment": {
                            "AWB": "%s",
                            "Status": {
                              "Status": "In Transit",
                              "StatusType": "UD",
                              "StatusLocation": "Bhopal Hub",
                              "Instructions": "Scanned via fallback poller"
                            }
                          }
                        }
                      ]
                    }
                    """.formatted(SAMPLE_AWB);
            sendJson(exchange, 200, body);
        });

        delhiveryStubServer.start();

        // Wire in-memory OrderRepository backed by orderStore
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId("ord-" + (orderStore.size() + 1));
            }
            orderStore.put(o.getId(), o);
            return o;
        });
        lenient().when(orderRepository.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(orderStore.get(inv.<String>getArgument(0))));
        lenient().when(orderRepository.findByCheckoutGroupId(anyString())).thenAnswer(inv -> {
            String cg = inv.getArgument(0);
            return orderStore.values().stream().filter(o -> cg.equals(o.getCheckoutGroupId())).toList();
        });
        lenient().when(mongoTemplate.findOne(any(Query.class), eq(Order.class))).thenAnswer(inv -> {
            Query q = inv.getArgument(0);
            String qStr = q.toString();
            return orderStore.values().stream()
                    .filter(o -> o.getSellerFulfillments() != null && o.getSellerFulfillments().stream()
                            .anyMatch(f -> f.getShipments() != null && f.getShipments().stream()
                                    .anyMatch(s -> s.getTrackingNumber() != null && qStr.contains(s.getTrackingNumber()))))
                    .findFirst()
                    .orElse(null);
        });

        lenient().when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenAnswer(inv -> {
            String qStr = inv.<Query>getArgument(0).toString();
            return orderStore.values().stream()
                    .filter(o -> (o.getIdempotencyKey() != null && qStr.contains(o.getIdempotencyKey().replaceAll(":S\\d+$", "")))
                            || (o.getOrderNumber() != null && qStr.contains(o.getOrderNumber().replaceAll("-S\\d+$", ""))))
                    .toList();
        });
        lenient().when(mongoTemplate.findById(anyString(), eq(Product.class))).thenAnswer(inv ->
                productStore.get(inv.<String>getArgument(0)));
        lenient().when(mongoTemplate.findOne(any(Query.class), eq(Product.class))).thenAnswer(inv -> {
            String qStr = inv.<Query>getArgument(0).toString();
            return productStore.values().stream()
                    .filter(p -> qStr.contains(p.getId()))
                    .findFirst()
                    .orElse(null);
        });
        lenient().when(mongoTemplate.findAll(eq(Product.class))).thenAnswer(inv ->
                new ArrayList<>(productStore.values()));
        lenient().when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenAnswer(inv ->
                new ArrayList<>(productStore.values()));
        lenient().when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(), eq(Product.class))).thenAnswer(inv -> {
            String qStr = inv.<Query>getArgument(0).toString();
            return productStore.values().stream()
                    .filter(p -> qStr.contains(p.getId()))
                    .findFirst()
                    .orElse(null);
        });

        // Real DelhiveryOneService with a real RestTemplate calling our local HTTP stub
        delhiveryOneService = new DelhiveryOneService(
                orderRepository,
                mongoTemplate,
                objectMapper,
                new RestTemplate(),
                webSocketService,
                null,
                true,
                delhiveryBaseUrl,
                "test-delhivery-token",
                "EBS Primary Warehouse",
                WEBHOOK_TOKEN
        );

        AdminOrderController adminOrderController = new AdminOrderController(
                orderPlacementService, orderQueryService, orderLifecycleService, rateLimitGuard, delhiveryOneService);
        DelhiveryWebhookController webhookController = new DelhiveryWebhookController(delhiveryOneService);
        VariantLabelController variantLabelController = new VariantLabelController(variantLabelRepository, mongoTemplate);

        CustomUserDetails adminUser = new CustomUserDetails(
                ADMIN_ID, "admin@ebsbiocrop.com", "9999999999", "", UserRole.ROLE_ADMIN,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), true);

        mockMvc = MockMvcBuilders
                .standaloneSetup(adminOrderController, webhookController, variantLabelController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                        return adminUser;
                    }
                })
                .build();
    }

    @AfterEach
    void tearDown() {
        if (delhiveryStubServer != null) {
            delhiveryStubServer.stop(0);
        }
    }

    @Test
    @DisplayName("E2E 1: 4 products across 2 sellers split into 2 separate PENDING orders & duplicate webhook is idempotent")
    void multiSellerSplitAndIdempotency() {
        Product p1 = buildProduct("prod-1", "seller-A", "Neem Oil", 500, 20);
        Product p2 = buildProduct("prod-2", "seller-A", "Humic Acid", 300, 15);
        Product p3 = buildProduct("prod-3", "seller-B", "Bio Fertilizer", 400, 10);
        Product p4 = buildProduct("prod-4", "seller-B", "Root Booster", 250, 12);
        productStore.put(p1.getId(), p1);
        productStore.put(p2.getId(), p2);
        productStore.put(p3.getId(), p3);
        productStore.put(p4.getId(), p4);

        FastrrCheckoutServiceImpl fastrrService = new FastrrCheckoutServiceImpl(
                mongoTemplate, orderRepository, objectMapper);
        ReflectionTestUtils.setField(fastrrService, "enabled", true);
        ReflectionTestUtils.setField(fastrrService, "apiKey", "test-fastrr-key");
        ReflectionTestUtils.setField(fastrrService, "secretKey", "test-fastrr-secret");

        Map<String, Object> payload = new HashMap<>();
        payload.put("cart_id", "CART-SPLIT-2026");
        payload.put("latest_stage", "ORDER_PLACED");
        payload.put("payment_status", "PAID");
        payload.put("payment_type", "PREPAID");
        payload.put("total_amount_payable", 1450);
        payload.put("customer_details", Map.of("name", "Kisan Test", "phone", "9876543210", "email", "kisan@test.com"));
        payload.put("shipping_address", Map.of("line1", "Mandi Road", "city", "Indore", "pincode", "452001"));
        payload.put("cart_data", Map.of("items", List.of(
                Map.of("product_id", "prod-1", "variant_id", "1", "quantity", 1, "price", 500),
                Map.of("product_id", "prod-2", "variant_id", "1", "quantity", 1, "price", 300),
                Map.of("product_id", "prod-3", "variant_id", "1", "quantity", 1, "price", 400),
                Map.of("product_id", "prod-4", "variant_id", "1", "quantity", 1, "price", 250)
        )));

        Map<String, Object> first = fastrrService.processOrderWebhook(payload);
        assertEquals("SUCCESS", first.get("status"));
        assertEquals(2, orderStore.size(), "4 items from 2 sellers MUST create 2 separate orders");

        List<Order> created = new ArrayList<>(orderStore.values());
        for (Order o : created) {
            assertEquals(OrderStatus.PENDING.name(), o.getOverallStatus(), "Every new Fastrr order MUST start as PENDING");
            assertEquals(2, o.getItems().size(), "Each seller order must contain only that seller's 2 items");
            assertTrue(o.getOrderNumber().startsWith("FASTRR-CART-SPLIT-2026-S"));
        }

        // Replay exact same webhook -> must be idempotent
        Map<String, Object> second = fastrrService.processOrderWebhook(payload);
        assertEquals("ALREADY_PROCESSED", second.get("status"));
        assertEquals(2, orderStore.size(), "Duplicate webhook must NOT create extra orders");

        // Confirm token issued for Fastrr session verifies cleanly and rejects tampering
        String confirmTok = fastrrService.issueConfirmToken("fastrr-tok-abc");
        assertTrue(fastrrService.verifyConfirmToken(confirmTok, "fastrr-tok-abc"));
        assertFalse(fastrrService.verifyConfirmToken(confirmTok, "wrong-tok"));
        assertFalse(fastrrService.verifyConfirmToken("9999999999.tampered", "fastrr-tok-abc"));
    }

    @Test
    @DisplayName("E2E 2: Full Happy Path + Negative Guards over real HTTP & MockMvc")
    void fullForwardLifecycleAndNegativeGuards() throws Exception {
        Order order = createPendingOrder("ord-happy", "FASTRR-HAPPY-1", "prod-1", 2);
        orderStore.put(order.getId(), order);

        // Guard 1: GET /label before booking -> 404 NOT_FOUND
        mockMvc.perform(get("/api/v1/admin/orders/ord-happy/label"))
                .andExpect(status().isNotFound());

        // Guard 2: POST /shipment/delhivery while still PENDING -> 409 CONFLICT
        mockMvc.perform(post("/api/v1/admin/orders/ord-happy/shipment/delhivery"))
                .andExpect(status().isConflict());

        // Step 1: Confirm order -> PENDING -> CONFIRMED AND auto-fetches Delhivery AWB + Label URL
        mockMvc.perform(post("/api/v1/admin/orders/ord-happy/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.sellerFulfillments[0].shipments[0].trackingNumber").value(SAMPLE_AWB))
                .andExpect(jsonPath("$.data.sellerFulfillments[0].shipments[0].labelReference").isNotEmpty());

        // Step 2: Explicit shipment endpoint remains idempotent if called again
        mockMvc.perform(post("/api/v1/admin/orders/ord-happy/shipment/delhivery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.sellerFulfillments[0].shipments[0].trackingNumber").value(SAMPLE_AWB));

        // Step 3: Download Label -> streams real PDF bytes & auto-marks PACKED
        String expectedBase64 = Base64.getEncoder().encodeToString(SAMPLE_PDF);
        mockMvc.perform(get("/api/v1/admin/orders/ord-happy/label"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallStatus").value("PACKED"))
                .andExpect(jsonPath("$.data.transitionedToPacked").value(true))
                .andExpect(jsonPath("$.data.waybill").value(SAMPLE_AWB))
                .andExpect(jsonPath("$.data.pdfBase64").value(expectedBase64));

        // Re-downloading label -> PDF served again, transitionedToPacked=false, stays PACKED
        mockMvc.perform(get("/api/v1/admin/orders/ord-happy/label"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallStatus").value("PACKED"))
                .andExpect(jsonPath("$.data.transitionedToPacked").value(false));

        // Guard 4: Unauthorized Delhivery webhook -> 401
        mockMvc.perform(post("/api/v1/webhooks/delhivery/tracking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Delhivery-Token", "wrong-token")
                        .content(delhiveryWebhookJson(SAMPLE_AWB, "Picked Up", "UD", "Indore Hub", "Picked up")))
                .andExpect(status().isUnauthorized());

        // Step 4: Webhook Picked Up -> SHIPPED
        mockMvc.perform(post("/api/v1/webhooks/delhivery/tracking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Delhivery-Token", WEBHOOK_TOKEN)
                        .content(delhiveryWebhookJson(SAMPLE_AWB, "Picked Up", "UD", "Indore Hub", "Picked up")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("SHIPPED"));

        // Step 5: Fallback Pull Sync over real HTTP -> IN_TRANSIT (from stub /api/v1/packages/json/)
        mockMvc.perform(post("/api/v1/admin/orders/ord-happy/sync-tracking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallStatus").value("IN_TRANSIT"));

        // Step 6: Webhook Dispatched -> OUT_FOR_DELIVERY
        mockMvc.perform(post("/api/v1/webhooks/delhivery/tracking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Delhivery-Token", WEBHOOK_TOKEN)
                        .content(delhiveryWebhookJson(SAMPLE_AWB, "Dispatched", "UD", "Indore DC", "Out for delivery")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("OUT_FOR_DELIVERY"));

        // Step 7: Webhook Delivered (DL) -> DELIVERED
        mockMvc.perform(post("/api/v1/webhooks/delhivery/tracking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Delhivery-Token", WEBHOOK_TOKEN)
                        .content(delhiveryWebhookJson(SAMPLE_AWB, "Delivered", "DL", "Indore", "Delivered to customer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("DELIVERED"));

        // Guard 5: Out-of-order replay after DELIVERED cannot regress status
        mockMvc.perform(post("/api/v1/webhooks/delhivery/tracking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Delhivery-Token", WEBHOOK_TOKEN)
                        .content(delhiveryWebhookJson(SAMPLE_AWB, "In Transit", "UD", "Old Hub", "Late duplicate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("DELIVERED"));
    }

    @Test
    @DisplayName("E2E 3: Full RTO Lifecycle + Manual Verify RTO & Restock Idempotency")
    void fullRtoLifecycleAndManualRestockSafety() throws Exception {
        Order order = createPendingOrder("ord-rto", "FASTRR-RTO-1", "prod-rto", 3);
        orderStore.put(order.getId(), order);

        // Confirm -> Book Delhivery -> Download Label (PACKED)
        mockMvc.perform(post("/api/v1/admin/orders/ord-rto/confirm")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/orders/ord-rto/shipment/delhivery")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/orders/ord-rto/label")).andExpect(status().isOk());

        // RTO_INITIATED -> RTO_IN_TRANSIT -> RTO_DELIVERED
        mockMvc.perform(post("/api/v1/webhooks/delhivery/tracking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Delhivery-Token", WEBHOOK_TOKEN)
                        .content(delhiveryWebhookJson(SAMPLE_AWB, "Pending", "RT", "Indore", "Customer refused parcel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("RTO_INITIATED"));

        mockMvc.perform(post("/api/v1/webhooks/delhivery/tracking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Delhivery-Token", WEBHOOK_TOKEN)
                        .content(delhiveryWebhookJson(SAMPLE_AWB, "In Transit", "RT", "Bhopal Hub", "Returning to origin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("RTO_IN_TRANSIT"));

        mockMvc.perform(post("/api/v1/webhooks/delhivery/tracking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Delhivery-Token", WEBHOOK_TOKEN)
                        .content(delhiveryWebhookJson(SAMPLE_AWB, "RTO Delivered", "RT", "Warehouse", "Returned to seller")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("RTO_DELIVERED"));

        // Stock MUST NOT have been auto-restocked yet
        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class), any(), eq(Product.class));

        // Admin clicks Verify RTO & Restock -> stock incremented ONCE
        mockMvc.perform(post("/api/v1/admin/orders/ord-rto/rto/restock"))
                .andExpect(status().isOk());
        verify(mongoTemplate, times(1)).findAndModify(any(Query.class), any(Update.class), any(), eq(Product.class));

        // Second click on Verify RTO & Restock -> idempotent, NEVER double-restocks
        mockMvc.perform(post("/api/v1/admin/orders/ord-rto/rto/restock"))
                .andExpect(status().isOk());
        verify(mongoTemplate, times(1)).findAndModify(any(Query.class), any(Update.class), any(), eq(Product.class));
    }

    @Test
    @DisplayName("E2E 4: Variant Label Higher-Unit Conversion (g -> kg, ml -> Ltr)")
    void variantLabelHigherUnitConversion() throws Exception {
        when(variantLabelRepository.findByNormalizedLabel(anyString())).thenReturn(Optional.empty());
        AtomicInteger idSeq = new AtomicInteger(1);
        when(variantLabelRepository.save(any(VariantLabel.class))).thenAnswer(inv -> {
            VariantLabel vl = inv.getArgument(0);
            vl.setId("vl-" + idSeq.getAndIncrement());
            return vl;
        });

        // 100g x 10 = 1000g (> 999) -> 1 kg (100g x 10 Qty)
        mockMvc.perform(post("/api/v1/variant-labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packSize\":100,\"unit\":\"g\",\"packQuantity\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.label").value("1 kg (100g x 10 Qty)"));

        // 500ml x 3 = 1500ml (> 999) -> 1.5 Ltr (500ml x 3 Qty)
        mockMvc.perform(post("/api/v1/variant-labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packSize\":500,\"unit\":\"ml\",\"packQuantity\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.label").value("1.5 Ltr (500ml x 3 Qty)"));

        // 100g x 5 = 500g (<= 999) -> stays in g
        mockMvc.perform(post("/api/v1/variant-labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packSize\":100,\"unit\":\"g\",\"packQuantity\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.label").value("500 g (100g x 5 Qty)"));
    }

    @Test
    @DisplayName("E2E 5: OTP Store & Cooldown work cleanly via in-memory fallback when Redis is unreachable")
    void otpRedisFallbackWhenRedisDown() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Redis unreachable"));

        SensitiveIdentifierHasher hasher = new SensitiveIdentifierHasher(new MockEnvironment(), "test-secret");

        RedisOtpCooldown cooldown = new RedisOtpCooldown(stringRedisTemplate, hasher, true);
        assertEquals(0L, cooldown.acquire("sms", "9876543210", Duration.ofSeconds(60), "tok-1"),
                "First OTP cooldown reservation should succeed even when Redis is down");
        assertTrue(cooldown.acquire("sms", "9876543210", Duration.ofSeconds(60), "tok-2") > 0,
                "Second immediate OTP request should be rate-limited by in-memory cooldown");

        RedisOtpStore store = new RedisOtpStore(stringRedisTemplate, hasher, true);
        String entryId = store.save("sms", "9876543210", "hashed-otp-123", Duration.ofMinutes(5));
        assertNotNull(entryId);
        RedisOtpStore.Verification verification = store.beginVerification("sms", "9876543210", 3);
        assertEquals("hashed-otp-123", verification.encodedOtp());
        store.complete("sms", "9876543210", verification);
    }

    // --- Helpers ---

    private Product buildProduct(String id, String sellerId, String title, double price, int stock) {
        Product p = new Product();
        p.setId(id);
        p.setSellerId(sellerId);
        p.setTitle(title);
        ProductVariant v = new ProductVariant();
        v.setId(id + "-v1");
        v.setLabel("1 kg");
        v.setDisplayRate(price);
        v.setPrintedMrp(price + 100);
        v.setStock(stock);
        p.setVariants(new ArrayList<>(List.of(v)));
        return p;
    }

    private Order createPendingOrder(String id, String orderNumber, String productId, int qty) {
        Order o = new Order();
        o.setId(id);
        o.setOrderNumber(orderNumber);
        o.setOverallStatus(OrderStatus.PENDING.name());
        o.setStatusHistory(new ArrayList<>());

        OrderAddressSnapshot addr = new OrderAddressSnapshot();
        addr.setName("Ramesh Farmer");
        addr.setPhoneNumber("9876543210");
        addr.setVillageArea("Near Mandi");
        addr.setCityTehsil("Indore");
        addr.setState("Madhya Pradesh");
        addr.setPincode("452001");
        o.setShippingAddress(addr);

        OrderPaymentDetails payment = new OrderPaymentDetails();
        payment.setPaymentType("PREPAID");
        o.setPayment(payment);

        Order.FinancialSummary fs = new Order.FinancialSummary();
        fs.setCurrentOrderTotal(BigDecimal.valueOf(1000));
        o.setFinancialSummary(fs);

        OrderItem item = new OrderItem();
        item.setItemOrderId("item-1");
        item.setProductId(productId);
        item.setVariantId(productId + "-v1");
        item.setQuantity(qty);
        item.setItemStatus(OrderStatus.PENDING.name());
        o.setItems(new ArrayList<>(List.of(item)));

        Order.SellerFulfillment ful = new Order.SellerFulfillment();
        ful.setId("ful-1");
        ful.setSellerId("seller-A");
        ful.setStatus(OrderStatus.PENDING.name());
        ful.setItemOrderIds(new ArrayList<>(List.of("item-1")));
        ful.setShipments(new ArrayList<>());
        o.setSellerFulfillments(new ArrayList<>(List.of(ful)));
        return o;
    }

    private String delhiveryWebhookJson(String awb, String status, String statusType, String loc, String instructions) {
        return """
                {
                  "Shipment": {
                    "AWB": "%s",
                    "Status": {
                      "Status": "%s",
                      "StatusType": "%s",
                      "StatusLocation": "%s",
                      "Instructions": "%s"
                    }
                  }
                }
                """.formatted(awb, status, statusType, loc, instructions);
    }

    private void sendJson(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
