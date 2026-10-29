import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.Map;

/** Carefully scoped dry-run/apply utility for the two verified order address snapshots. */
public final class OrderAddress2Migration {
    private static final String DATABASE = System.getenv().getOrDefault("MONGODB_DATABASE", "seller_hub");
    private static final String EXPECTED_ADDRESS = "Near Aura Mall";
    private static final List<Target> TARGETS = List.of(
            new Target("6ae031e388ab45694c50f087", "ORD-6AE2D682477DABE731E2C1F2-000016"),
            new Target("6ae18eb0961e715c38fbca10", "ORD-6AE2D682477DABE731E2C1F3-000017"));

    private record Target(String id, String orderNumber) { }

    public static void main(String[] args) {
        boolean apply = List.of(args).contains("--apply");
        String uri = System.getenv("MONGODB_URI");
        if (uri == null || uri.isBlank()) throw new IllegalStateException("MONGODB_URI is required.");

        try (MongoClient client = MongoClients.create(uri)) {
            MongoCollection<Document> orders = client.getDatabase(DATABASE).getCollection("orders");
            long legacyDocumentsBefore = countLegacyAddressDocuments(orders);
            List<Document> before = TARGETS.stream().map(target -> verifiedOrder(orders, target)).toList();
            int pending = 0;
            for (int i = 0; i < TARGETS.size(); i++) {
                Document order = before.get(i);
                boolean shippingLegacy = hasLegacyAddress(order, "shippingAddress");
                boolean billingLegacy = hasLegacyAddress(order, "billingAddress");
                if (shippingLegacy || billingLegacy) pending++;
                System.out.printf("%s | %s | shipping: %s | billing: %s | %s%n",
                        TARGETS.get(i).id(), TARGETS.get(i).orderNumber(),
                        addressValue(order, "shippingAddress"), addressValue(order, "billingAddress"),
                        shippingLegacy || billingLegacy ? "would rename legacy field" : "already aligned");
            }
            System.out.printf("Mode: %s; database: %s; targeted documents: %d; documents needing change: %d%n",
                    apply ? "APPLY" : "DRY RUN", DATABASE, TARGETS.size(), pending);
            System.out.printf("Orders containing addressLine2 before operation: %d%n", legacyDocumentsBefore);
            if (!apply || pending == 0) return;

            for (int i = 0; i < TARGETS.size(); i++) {
                Target target = TARGETS.get(i);
                Document order = before.get(i);
                boolean shippingLegacy = hasLegacyAddress(order, "shippingAddress");
                boolean billingLegacy = hasLegacyAddress(order, "billingAddress");
                if (!shippingLegacy && !billingLegacy) continue;

                Bson filter = Filters.and(
                        Filters.eq("_id", new ObjectId(target.id())),
                        Filters.eq("orderNumber", target.orderNumber()),
                        safeAddressMatch("shippingAddress", order),
                        safeAddressMatch("billingAddress", order));
                Bson update = Updates.combine(
                        Updates.rename("shippingAddress.addressLine2", "shippingAddress.address2"),
                        Updates.rename("billingAddress.addressLine2", "billingAddress.address2"));
                long matched = orders.updateOne(filter, update).getMatchedCount();
                if (matched != 1) throw new IllegalStateException("Safety filter no longer matches order " + target.id());
            }

            for (Target target : TARGETS) {
                Document order = verifiedOrder(orders, target);
                if (hasLegacyAddress(order, "shippingAddress") || hasLegacyAddress(order, "billingAddress")
                        || !EXPECTED_ADDRESS.equals(address2(order, "shippingAddress"))
                        || !EXPECTED_ADDRESS.equals(address2(order, "billingAddress"))) {
                    throw new IllegalStateException("Post-update verification failed for order " + target.id());
                }
            }
            System.out.printf("Orders containing addressLine2 after operation: %d%n",
                    countLegacyAddressDocuments(orders));
            System.out.println("Applied and verified the targeted address2 field migration.");
        }
    }

    private static long countLegacyAddressDocuments(MongoCollection<Document> orders) {
        return orders.countDocuments(Filters.or(
                Filters.exists("shippingAddress.addressLine2", true),
                Filters.exists("billingAddress.addressLine2", true)));
    }

    private static Document verifiedOrder(MongoCollection<Document> orders, Target target) {
        Document order = orders.find(Filters.eq("_id", new ObjectId(target.id()))).first();
        if (order == null || !target.orderNumber().equals(order.getString("orderNumber"))) {
            throw new IllegalStateException("Expected order ID and order number do not match: " + target.id());
        }
        verifyAddressValue(order, "shippingAddress");
        verifyAddressValue(order, "billingAddress");
        return order;
    }

    private static void verifyAddressValue(Document order, String field) {
        String legacy = legacyAddress(order, field);
        String current = address2(order, field);
        if ((legacy != null && !EXPECTED_ADDRESS.equals(legacy))
                || (current != null && !EXPECTED_ADDRESS.equals(current))
                || (legacy == null && current == null)) {
            throw new IllegalStateException("Unexpected " + field + " address values; refusing to update.");
        }
    }

    private static Bson safeAddressMatch(String field, Document order) {
        String legacy = legacyAddress(order, field);
        String current = address2(order, field);
        if (legacy != null) {
            return Filters.and(
                    Filters.eq(field + ".addressLine2", legacy),
                    Filters.or(
                            Filters.exists(field + ".address2", false),
                            Filters.eq(field + ".address2", legacy)));
        }
        return Filters.and(
                Filters.exists(field + ".addressLine2", false),
                Filters.eq(field + ".address2", current));
    }

    private static boolean hasLegacyAddress(Document order, String field) {
        return legacyAddress(order, field) != null;
    }

    private static String addressValue(Document order, String field) {
        String current = address2(order, field);
        return current != null ? "address2=" + current : "addressLine2=" + legacyAddress(order, field);
    }

    private static String legacyAddress(Document order, String field) {
        Object value = nested(order, field, "addressLine2");
        return value instanceof String string ? string : null;
    }

    private static String address2(Document order, String field) {
        Object value = nested(order, field, "address2");
        return value instanceof String string ? string : null;
    }

    private static Object nested(Document order, String field, String key) {
        Object address = order.get(field);
        return address instanceof Map<?, ?> map ? map.get(key) : null;
    }
}
