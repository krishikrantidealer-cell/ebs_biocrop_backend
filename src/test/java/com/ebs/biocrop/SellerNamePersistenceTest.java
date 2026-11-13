package com.ebs.biocrop;

import com.ebs.biocrop.entity.Order;
import com.ebs.biocrop.entity.OrderItem;
import com.ebs.biocrop.entity.OrderSellerSnapshot;
import com.ebs.biocrop.entity.Product;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;

import static org.junit.jupiter.api.Assertions.*;

class SellerNamePersistenceTest {
    private MappingMongoConverter converter() throws Exception {
        MongoCustomConversions conversions = MongoCustomConversions.create(adapter -> { });
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return converter;
    }

    @Test
    void productDoesNotMapVendorAndWritesOnlySellerName() throws Exception {
        MappingMongoConverter converter = converter();
        Product productWithObsoleteField = converter.read(Product.class,
                new Document("_id", "product-id").append("vendor", "Obsolete Shop"));
        assertNull(productWithObsoleteField.getSellerName());

        Product current = new Product();
        current.setSellerName("Current Shop");
        Document document = new Document();
        converter.write(current, document);
        assertEquals("Current Shop", document.getString("sellerName"));
        assertFalse(document.containsKey("vendor"));
    }

    @Test
    void orderStoresSellerNameInFulfillmentSnapshotNotLineItem() throws Exception {
        MappingMongoConverter converter = converter();
        Order order = new Order();
        Order.SellerFulfillment fulfillment = new Order.SellerFulfillment();
        OrderSellerSnapshot sellerSnapshot = new OrderSellerSnapshot();
        sellerSnapshot.setTradeName("Historic Shop");
        fulfillment.setSellerSnapshot(sellerSnapshot);
        order.setSellerFulfillments(java.util.List.of(fulfillment));
        OrderItem current = new OrderItem();
        current.setItemOrderId("item-1");
        current.setSellerFulfillmentId(fulfillment.getId());
        order.setItems(java.util.List.of(current));
        Document document = new Document();
        converter.write(order, document);
        Document storedFulfillment = document.getList("sellerFulfillments", Document.class).getFirst();
        Document storedSnapshot = storedFulfillment.get("sellerSnapshot", Document.class);
        Document storedItem = document.getList("items", Document.class).getFirst();
        Document storedPayment = document.get("payment", Document.class);
        Document storedFinancials = document.get("financialSummary", Document.class);
        assertEquals("Historic Shop", storedSnapshot.getString("tradeName"));
        assertEquals(2, document.getInteger("schemaVersion"));
        assertTrue(document.containsKey("checkoutGroupId"));
        assertTrue(document.containsKey("idempotencyKey"));
        assertTrue(document.containsKey("customerDecisionWindows"));
        assertTrue(storedFulfillment.containsKey("responseDeadlineAt"));
        assertTrue(storedFulfillment.containsKey("dispatchSlaDeadlineAt"));
        assertTrue(storedFulfillment.containsKey("shipments"));
        assertTrue(storedFulfillment.containsKey("delayReview"));
        assertTrue(storedItem.containsKey("itemOrderId"));
        assertTrue(storedItem.containsKey("taxAmount"));
        assertTrue(storedPayment.containsKey("transactions"));
        assertTrue(storedPayment.containsKey("refunds"));
        assertTrue(storedFinancials.containsKey("codDue"));
        assertFalse(storedItem.containsKey("sellerName"));
        assertFalse(storedItem.containsKey("vendor"));
    }
}
