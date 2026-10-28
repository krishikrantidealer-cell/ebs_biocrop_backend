package com.ebs.biocrop;

import com.ebs.biocrop.entity.OrderItem;
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
    void productReadsLegacyVendorButWritesSellerName() throws Exception {
        MappingMongoConverter converter = converter();
        Product legacy = converter.read(Product.class,
                new Document("_id", "product-id").append("vendor", "Legacy Shop"));
        assertEquals("Legacy Shop", legacy.getSellerName());

        Product current = new Product();
        current.setSellerName("Current Shop");
        Document document = new Document();
        converter.write(current, document);
        assertEquals("Current Shop", document.getString("sellerName"));
        assertFalse(document.containsKey("vendor"));
    }

    @Test
    void historicalOrderItemReadsLegacyVendorButWritesSellerName() throws Exception {
        MappingMongoConverter converter = converter();
        OrderItem legacy = converter.read(OrderItem.class,
                new Document("_id", "item-id").append("vendor", "Historic Shop"));
        assertEquals("Historic Shop", legacy.getSellerName());

        OrderItem current = new OrderItem();
        current.setSellerName("Historic Shop");
        Document document = new Document();
        converter.write(current, document);
        assertEquals("Historic Shop", document.getString("sellerName"));
        assertFalse(document.containsKey("vendor"));
    }
}
