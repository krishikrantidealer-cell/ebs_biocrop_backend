package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.OrderItem;
import com.ebs.biocrop.entity.enums.OrderStatus;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.repository.OrderRepository;
import com.ebs.biocrop.websocket.WebSocketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DelhiveryOneServiceTest {

    private OrderRepository orderRepository;
    private MongoTemplate mongoTemplate;
    private WebSocketService webSocketService;
    private DelhiveryOneService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        webSocketService = mock(WebSocketService.class);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new DelhiveryOneService(
                orderRepository,
                mongoTemplate,
                new ObjectMapper(),
                mock(RestTemplate.class),
                webSocketService,
                null,
                false,
                "https://staging-express.delhivery.com",
                "",
                "",
                "secret-hook-token"
        );
    }

    private Order sampleOrder(String status) {
        Order order = new Order();
        order.setId("ord-1");
        order.setOrderNumber("FASTRR-TEST-1");
        order.setOverallStatus(status);
        Order.SellerFulfillment f = new Order.SellerFulfillment();
        f.setId("ful-1");
        f.setStatus(status);
        f.setShipments(new ArrayList<>());
        order.setSellerFulfillments(new ArrayList<>(List.of(f)));
        OrderItem item = new OrderItem();
        item.setProductId("prod-1");
        item.setVariantId("var-1");
        item.setQuantity(2);
        item.setItemStatus(status);
        order.setItems(new ArrayList<>(List.of(item)));
        order.setStatusHistory(new ArrayList<>());
        return order;
    }

    @Test
    void confirmOrderTransitionsPendingToConfirmed() {
        Order order = sampleOrder(OrderStatus.PENDING.name());
        when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));

        Order confirmed = service.confirmOrder("admin-1", "ord-1");

        assertEquals(OrderStatus.CONFIRMED.name(), confirmed.getOverallStatus());
        assertEquals(OrderStatus.CONFIRMED.name(), confirmed.getSellerFulfillments().getFirst().getStatus());
        verify(webSocketService).broadcast(eq("orders:updated"), any());
    }

    @Test
    void downloadLabelRejectsWhenNoLabelStoredInBackend() {
        Order order = sampleOrder(OrderStatus.CONFIRMED.name());
        when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));

        AppException ex = assertThrows(AppException.class,
                () -> service.downloadLabelAndMarkPacked("admin-1", "ord-1"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void downloadLabelMarksConfirmedOrderPacked() {
        Order order = sampleOrder(OrderStatus.CONFIRMED.name());
        Order.Shipment s = new Order.Shipment();
        s.setTrackingNumber("DLV123456789");
        s.setLabelReference("https://track.delhivery.com/api/p/packing_slip?wbns=DLV123456789&pdf=true");
        order.getSellerFulfillments().getFirst().getShipments().add(s);
        when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));

        DelhiveryOneService.LabelDownloadResult res = service.downloadLabelAndMarkPacked("admin-1", "ord-1");

        assertTrue(res.transitionedToPacked());
        assertEquals(OrderStatus.PACKED.name(), res.overallStatus());
        assertEquals(OrderStatus.PACKED.name(), order.getOverallStatus());
        assertNotNull(s.getLabelDownloadedAt());
    }

    @Test
    void trackingWebhookAdvancesForwardAndRtoStatesAndRestocksOnRtoDelivered() {
        Order order = sampleOrder(OrderStatus.PACKED.name());
        Order.Shipment s = new Order.Shipment();
        s.setCarrier("DELHIVERY");
        s.setTrackingNumber("DLV999888777");
        order.getSellerFulfillments().getFirst().getShipments().add(s);
        when(mongoTemplate.findOne(any(Query.class), eq(Order.class))).thenReturn(order);
        when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));

        // 1. SHIPPED
        service.processTrackingWebhook(Map.of(
                "waybill", "DLV999888777",
                "status", "Picked Up",
                "status_type", "UD",
                "location", "Bhopal Hub"
        ), "secret-hook-token");
        assertEquals(OrderStatus.SHIPPED.name(), order.getOverallStatus());

        // 2. IN_TRANSIT
        service.processTrackingWebhook(Map.of(
                "waybill", "DLV999888777",
                "status", "In Transit",
                "status_type", "UD",
                "location", "Indore Hub"
        ), "secret-hook-token");
        assertEquals(OrderStatus.IN_TRANSIT.name(), order.getOverallStatus());

        // 3. RTO_INITIATED
        service.processTrackingWebhook(Map.of(
                "waybill", "DLV999888777",
                "status", "Pending",
                "status_type", "RT",
                "instructions", "Customer refused COD delivery"
        ), "secret-hook-token");
        assertEquals(OrderStatus.RTO_INITIATED.name(), order.getOverallStatus());
        assertEquals("Customer refused COD delivery", s.getRtoReason());

        // 4. RTO_DELIVERED
        service.processTrackingWebhook(Map.of(
                "waybill", "DLV999888777",
                "status", "RTO Delivered",
                "status_type", "RT"
        ), "secret-hook-token");
        assertEquals(OrderStatus.RTO_DELIVERED.name(), order.getOverallStatus());

        // 5. Manual restock (idempotent)
        service.restockRtoOrder("admin-1", "ord-1");
        assertTrue(Boolean.TRUE.equals(s.getRtoRestocked()));
        verify(mongoTemplate, times(1)).updateFirst(any(Query.class), any(Update.class), eq(com.ebs.biocrop.entity.Product.class));
    }
}
