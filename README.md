# EBS BioCrop Web Platform

Spring Boot 3.4.3 backend with MongoDB Atlas (`seller_hub`), role-specific authentication, JWT security, profile management, cart operations, and seller-managed product listings.

---

## 📌 Tech Stack & Architecture

* **Language & Runtime:** Java 21 (LTS)
* **Framework:** Spring Boot 3.4.3 / Spring Security 6 (Stateless JWT)
* **Build Tool:** Apache Maven (`mvnw.cmd`)
* **Database:** MongoDB Atlas (Cluster: `KrishiKranti`, Database: `seller_hub`)
* **MongoDB Collections:** `users`, `carts`, `products`, `categories`, and `wishlists`.
* **Catalog direction:** Product content follows the latest supplied schema fields, including variant unit, shipped-by, SWG, GST, discounts, stock, ratings, refund policy, and package dimensions; old workbook-based product data and fixtures have been removed; the replacement catalog will be added when approved. Category nodes are individual documents in one collection, linked with `parentId` and annotated with `level`. Seller-owned product listings carry the seller reference; seller onboarding details and document storage remain deferred.
* **Zero DB Overhead for OTP:**
  * OTP hashes and verification attempts are maintained in a thread-safe in-memory cache with a 5-minute expiry and a maximum of 3 attempts per code.
  * Phone and email resend cooldowns are acquired atomically in Redis with a 60-second TTL, so they are shared across backend instances.
  * No OTP collection or document in MongoDB.
* **Role-specific Authentication:**
  * Customers use phone OTP only. Sellers use phone OTP or email OTP and can set an email password after authenticated OTP login. Admins use email OTP or email/password only. Password reset uses a one-time, expiring email link.
  * Admin and seller identities must be provisioned by the platform; there is no registration endpoint. Email delivery uses SMTP when configured; development can use console delivery.
* **User Roles:**
  * The `UserRole` enum includes `ROLE_CUSTOMER`, `ROLE_SELLER`, and `ROLE_ADMIN`.
  * Phone OTP creates customer accounts for unknown numbers and preserves an existing account role. Admin accounts are rejected by phone OTP. Seller business onboarding remains offline.
* **User Schema (`users` collection):**
  * `id` (`String` / MongoDB ObjectId)
  * `phoneNumber` (`String`, unique indexed)
  * `firstName`, `lastName` (`String`, captured during profile completion)
  * `address` (structured delivery/business address)
  * `role` (`UserRole`: `ROLE_CUSTOMER`, `ROLE_SELLER`, `ROLE_ADMIN`)
  * `createdAt` (`LocalDateTime`)
  * `updatedAt` (`LocalDateTime`)

---

## 🌿 Git Branch Policy

> All work is kept strictly local on this branch. Direct pushes to `main` are prohibited.
---

## 🚀 Running the Project Locally

### 1. Build and Run Tests
```powershell
.\mvnw.cmd test
```

### 2. Start Application
```powershell
.\mvnw.cmd spring-boot:run
```
The server will start on port 8080 (http://localhost:8080). Spring Boot loads MONGODB_URI and JWT_SECRET from the git-ignored .env file for local development. The default Mongo URI targets localhost; edit .env once if you use a different development database. The JWT secret must contain at least 32 bytes. Production must provide these values through deployment environment variables or a secret manager and must not deploy the local .env file.

No product or category spreadsheet is imported at startup. Existing database records are left untouched. OTP requests return `503` until an SMS provider is integrated. Console OTP delivery is permitted only when the `dev` profile and `app.otp.console-delivery.enabled=true` are both set; never enable this for real users.

---

## 🧪 API Testing Guide

### 1. Health Check (Public)
* **Method:** `GET`
* **URL:** `http://localhost:8080/api/v1/health`
* **Response (200 OK):**
```json
{
  "success": true,
  "message": "EBS Biocrop Web API is healthy and operational",
  "data": {
    "status": "UP",
    "service": "ebs_biocrop_web",
    "database": "MongoDB Atlas (seller_hub)",
    "security": "JWT + Spring Security 6"
  },
  "timestamp": "2026-09-22T11:15:00"
}
```

---

### 2. Generate / Send OTP (Public)
* **Method:** `POST`
* **URL:** `http://localhost:8080/api/v1/auth/otp/send`
* **Headers:** `Content-Type: application/json`
* **Body (raw JSON):**
```json
{
  "phoneNumber": "9876543210"
}
```
* **Response (200 OK):**
```json
{
  "success": true,
  "message": "OTP dispatched successfully",
  "data": {
    "phoneNumber": "98******10",
    "status": "OTP_SENT",
    "cooldownSeconds": 60,
    "expirationMinutes": 5,
    "message": "OTP has been successfully dispatched."
  },
  "timestamp": "2026-09-22T11:15:00"
}
```
> 💡 *Note for Testing:* With the `dev` Spring profile and `app.otp.console-delivery.enabled=true`, the generated OTP is logged to the console:
> ```text
> Development OTP for phone [98******10]: [123456]
> ```

---

### 3. Verify OTP & Obtain JWT (Public)
* **Method:** `POST`
* **URL:** `http://localhost:8080/api/v1/auth/otp/verify`
* **Headers:** `Content-Type: application/json`
* **Body (raw JSON):**
```json
{
  "phoneNumber": "9876543210",
  "otp": "123456"
}
```
Phone OTP creates a customer account for an unknown phone number and preserves the role of an existing account. Sellers can use phone OTP after their account has been assigned `ROLE_SELLER`; admin accounts are explicitly rejected by the phone OTP verification route. The response includes `isProfileComplete` so the client can direct customers to finish their profile.

### Admin and seller email/password authentication

* `POST /api/v1/auth/email/otp/send` and `POST /api/v1/auth/email/otp/verify` — email OTP for provisioned sellers and admins only.
* `POST /api/v1/auth/password/login` — email/password login for sellers and admins with a password already set.
* `POST /api/v1/auth/password/setup` — set a first password after OTP login; requires an authenticated `ROLE_SELLER` or `ROLE_ADMIN` token and a provisioned email.
* `POST /api/v1/auth/password/forgot` — request an email reset link. The response is generic to avoid disclosing whether an account exists.
* `POST /api/v1/auth/password/reset` — consume a one-time reset token and set a new password.

Passwords require 12–72 characters including uppercase, lowercase, a number, and a symbol. Reset tokens are stored as SHA-256 hashes and expire after 30 minutes. Configure `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, and `PASSWORD_RESET_URL` for real email delivery. A local `dev` profile with `MAIL_CONSOLE_DELIVERY_ENABLED=true` logs OTPs and reset links to the server console.
* **Response (200 OK):**
```json
{
  "success": true,
  "message": "Authentication successful",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "expiresInMs": 86400000,
    "userId": "66f01a...",
    "phoneNumber": "9876543210",
    "role": "ROLE_CUSTOMER",
    "isProfileComplete": false
  },
  "timestamp": "2026-09-22T11:15:00"
}
```

---

### Refresh Access Token
* **Method:** `POST`
* **URL:** `http://localhost:8080/api/v1/auth/token/refresh`
* **Body (raw JSON):**
```json
{
  "refreshToken": "<refresh_token_from_login>"
}
```
The endpoint rejects access tokens and returns a new access token with the original refresh token. Refreshing does not extend the original refresh-token expiry.

### Customer Profile

After OTP login, customers must submit first name, last name, village/area, address line 2, city/tehsil, state, and a six-digit pincode through `PUT /api/v1/user/profile`. OTP verification (`isVerified`) is separate from profile completion (`isProfileComplete`). The login response exposes `isProfileComplete`; customers should complete the profile before the future order/payment flow. Seller business, warehouse, GSTIN, and certificate details are deferred to a later onboarding phase; there is no public registration flow.

### Public Product Catalog

Catalog reads expose only available, admin-approved seller listings. The obsolete workbook records were removed and five clearly labeled TEST ONLY agricultural product documents are currently used for API development; replace them when the approved catalog arrives. List, category, and search responses are paginated (`page` starts at 0; `size` defaults to 20 and is limited to 100).

* `GET /api/v1/products?page=0&size=20` — list active products.
* `GET /api/v1/products/{id}` — fetch one active seller listing; inactive, unapproved, or missing products return 404.
* `GET /api/v1/products/category/{categoryId}?page=0&size=20` — filter by a category document ID and all active descendants up to level 2.
* `GET /api/v1/products/search?q=insecticide&page=0&size=20` — case-insensitive literal search across title and description.
### Public Categories

* `GET /api/v1/categories` — list category documents.
* `GET /api/v1/categories/{id}` — fetch one category document; an unknown ID returns 404.

Category routes are public and use the standard `ApiResponse` envelope. `GET /api/v1/categories?parentId={id}` returns a node's direct children; `GET /api/v1/categories?level=0`, `level=1`, and `level=2` list each depth. The application enforces a maximum depth of two parent links (levels 0–2). All nodes are documents in the same `categories` collection with `parentId` and computed `level`; there is no separate subcategory collection or embedded nested array. The former spreadsheet seeder has been removed. Existing embedded category documents can be converted using the opt-in category migration after explicit database approval; no startup migration creates backup collections. The old `migrate_db.js` script is disabled because it transformed obsolete data and dropped collections. Admins can create, update, and deactivate categories under `/api/v1/admin/categories`; `level` is computed from `parentId`.

### Redis rate limiting and public-read caches

The backend uses Redis counters for authentication rate limits. OTP send/verify, email OTP, password login/reset, and token refresh policies increment HMAC-SHA-256 phone/email/IP keys atomically and expire each counter at the configured window. Set a stable, secret `REDIS_KEY_HMAC_SECRET` of at least 32 UTF-8 bytes in every backend environment; production startup fails if it is missing. Phone and email OTP sends also acquire HMAC-protected Redis cooldown keys atomically with a 60-second TTL. A cooldown rejection returns `429` with `Retry-After`; a Redis failure fails closed with `503`. Authenticated cart writes are limited to 60 per user per minute (cart sync also has a 10 per minute limit); customer order creation is limited to 5 per user per minute, admin-assisted creation to 10 per admin per minute, and cancellation to 10 per actor per minute. These write limits are separate from business validation and return `429` with `Retry-After` when exceeded. For local Upstash TCP access, configure `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED=true`, and `REDIS_KEY_HMAC_SECRET` in the git-ignored `.env`; do not use the Upstash REST token. Rate-limit requests fail closed with `503` when Redis is unavailable, including in development. An in-memory fallback can be explicitly enabled for offline development with `RATE_LIMIT_DEV_IN_MEMORY_FALLBACK=true`; it is not shared across app instances and should not be used for production.

Public category list/detail responses are cached in Redis for 10 minutes, published-blog list/detail/facet responses for 5 minutes, and public product detail/list/search responses for 30 seconds. Successful category writes invalidate category and product catalog regions; order placement/cancellation invalidate product responses after MongoDB commits stock changes. Other product changes become visible after the 30-second product cache TTL. Region version keys invalidate all query variants without scanning Redis. If cache reads or writes fail, endpoints fall back to MongoDB; MongoDB remains authoritative, and checkout always re-reads current product/stock values. Orders, carts, and payment state are not cached.

### Seller Listings and Admin Review

`POST /api/v1/seller/products`, paginated `GET /api/v1/seller/products?page=0&size=20`, and `PUT /api/v1/seller/products/{id}` are restricted to `ROLE_SELLER`. Product ownership and variant IDs are assigned by the server. Sellers can select an active leaf category at level 0, 1, or 2. New or edited listings are saved as `PENDING_REVIEW` and unavailable to public catalog reads until an admin approves them with `PATCH /api/v1/admin/products/{id}/review?approved=true`; rejection uses `approved=false`. Admins can pause/resume availability at `PATCH /api/v1/admin/products/{id}/availability?available=false|true` and feature/unfeature approved listings at `PATCH /api/v1/admin/products/{id}/featured?featured=true|false`. The admin queue is `GET /api/v1/admin/products?status=PENDING_REVIEW&page=0&size=20`. Product variants support structured pack size/quantity, unit, shipped-by value, SWG, commercial fields, and stock. Product dimensions are in centimeters. Discount amount and percentage and price per ml/g are derived from Printed MRP, Display Rate, and normalized package size. GST percent is stored for later tax policy decisions. Cart add/update/remove operations use variant MongoDB IDs and current Display Rate/stock fields. Checkout order placement creates one linked order per seller; shipping charges and payment initiation are not implemented yet.

### Orders and Checkout

Customer order endpoints require `ROLE_CUSTOMER`; seller endpoints require `ROLE_SELLER` and scope reads to the authenticated seller. `POST /api/v1/orders/create` accepts `paymentMethod` (`PREPAID` or `PARTIAL`), a required shipping address, and an optional billing address (defaults to the shipping address). For `PARTIAL`, customer checkout must send `advancePercentage` as 10, 20, or 50. Admins can create orders from a customer's cart through `POST /api/v1/admin/orders/customers/{customerId}/create`; that route accepts a custom whole-number `advancePercentage` from 1 to 99 for `PARTIAL` orders and records the admin actor in status history. For `PREPAID`, omit `advancePercentage`; the backend records a 100% advance due. The server rereads catalog data, groups all cart items by seller, atomically reserves stock and saves one order per seller in the `orders` collection, links the resulting documents with one `checkoutGroupId`, then clears the cart in the same MongoDB transaction. Order numbers have the form `ORD-{sellerId}-{sequence}`. Shipping is free and stored as zero for now. Checkout creates orders but does not verify or initiate a payment. `paymentState` starts as `AWAITING_PAYMENT`; selecting a method or advance percentage alone is not proof that money was paid. The order stores `advancePercentage` and `advanceAmountDue` separately from verified payment amounts. `paymentStatus` stays unset until trusted payment confirmation; afterward it is `PAID` or `PARTIALLY_PAID`. For a confirmed cumulative payment, `remainingAmount = max(totalAmount - amountPaid, 0)`, `paidPercentage = amountPaid / totalAmount * 100`, and `remainingPercentage = remainingAmount / totalAmount * 100` (amounts and percentages rounded to two decimals; percentages are zero when total is zero). The first confirmed payment must equal the advance due; subsequent confirmed payments may bring the cumulative paid amount up to the total. `PREPAID` orders can be recorded as paid only when the full total is confirmed. The backend currently has no payment confirmation endpoint/provider integration, so checkout does not yet populate these final statuses from a real payment.

Canonical order statuses for new lifecycle operations are `ORDERED`, `CONFIRMED`, `PACKED`, `SHIPPED`, `DELIVERED`, `CANCELED`, `RTO_SHIPPED`, `RTO_DELIVERED`, and `HOLD`. Seller display maps `ORDERED` to `PENDING`; customer display maps both `ORDERED` and `CONFIRMED` to `ORDERED`. Other statuses display as their canonical labels except customer RTO labels, which are phrased as “Return to origin — shipped/delivered.” The target `orders` collection was empty when validation was installed. Display mapping retains compatibility for older status spellings if legacy data is imported later; no automatic startup migration is performed.

Customer and seller order endpoints return an `OrderResponse` projection. Its `orderStatus` is the role-specific display value: customers see `ORDERED` for both `ORDERED` and `CONFIRMED`, while sellers see `PENDING` for `ORDERED`; customers see friendly “Return to origin — shipped/delivered” labels for RTO states. The response includes `paymentState`, `paymentStatus`, `advancePercentage`, `advanceAmountDue`, and a nested `payment` summary with `amountPaid`, `advanceAmount`, `remainingAmount`, `paidPercentage`, and `remainingPercentage`. Sellers also receive the customer name and phone number for fulfillment. Internal owner IDs, stock-reservation state, and payment reconciliation transactions are excluded. The status-transition API and payment confirmation flow are not implemented yet; `Order.recordConfirmedPayment` is a domain operation for use only by a future trusted payment-confirmation flow.

MongoDB schema validation has been applied to `KrishiKranti.seller_hub.orders`. The collection contained zero documents at the time, so no existing order documents were rewritten. The validator requires the core order/payment fields, restricts statuses and numeric ranges, and checks that `PREPAID` uses 100% while `PARTIAL` uses 1–99%; customer-specific 10/20/50 enforcement and admin custom percentages are enforced by the respective API paths. `scripts/migrate_orders_schema.cjs` is the guarded migration utility; it defaults to dry-run, refuses an upgrade if the collection is non-empty or has an unexpected validator, and safely no-ops once the current validator is installed.

* `POST /api/v1/orders/create` — place orders from the authenticated customer's cart; partial orders require `advancePercentage` 10, 20, or 50.
* `POST /api/v1/admin/orders/customers/{customerId}/create` — admin-assisted order creation with a custom 1–99% partial advance.
* `GET /api/v1/orders/customer?page=0&size=20` — list the authenticated customer's orders.
* `GET /api/v1/orders/{orderId}` — retrieve one of the authenticated customer's orders.
* `GET /api/v1/orders/group/{checkoutGroupId}` — list the authenticated customer's seller orders from one checkout.
* `GET /api/v1/seller/orders?page=0&size=20` — list the authenticated seller's orders.
* `GET /api/v1/seller/orders/{orderId}` — retrieve one order belonging to that seller.
* `POST /api/v1/orders/{orderId}/cancel` — customer cancellation of an unpaid pending order; returns reserved stock.
* `POST /api/v1/seller/orders/{orderId}/cancel` — seller cancellation of an unpaid pending order; returns reserved stock.

Cancellation is currently limited to `ORDERED` orders with no recorded payment (displayed to sellers as `PENDING`). A removed variant has no active catalog stock record to restore; if its parent product is missing, cancellation fails so the reservation is not silently discarded. Payment/refund handling and status transitions remain subject to final business rules.

### Role Provisioning

Public OTP verification never accepts or changes a role: new phone numbers receive `ROLE_CUSTOMER`, and existing users retain their stored role. Admins can assign `ROLE_CUSTOMER`, `ROLE_SELLER`, or `ROLE_ADMIN` using `PATCH /api/v1/admin/users/{userId}/role` with `{"role":"ROLE_SELLER"}`. The EBS team can promote an offline-onboarded seller after that person first logs in. After a role change, the person must refresh the token or log in again for the new role claim. The first admin account must be provisioned by an authorized operator before admin-only workflows can be used; there is no public admin registration flow.
### Customer Wishlist (JWT required)

Wishlist routes are restricted to `ROLE_CUSTOMER`. The owner is taken from the authenticated JWT; clients must not send a user ID.

* `GET /api/v1/wishlist` — return the current customer’s saved variants; a missing wishlist returns an empty list and does not create a document.
* `PUT /api/v1/wishlist/items/{variantId}` — save an active catalog variant by its embedded MongoDB `_id`. Repeating the request does not create duplicates. Out-of-stock variants may still be saved.
* `DELETE /api/v1/wishlist/items/{variantId}` — remove a specific variant; repeating the request is safe.
* `DELETE /api/v1/wishlist` — clear the current customer’s saved variants.

The `wishlists` collection stores one document per customer with a unique user ID, a `variantIds` array, and a `variantProductIds` map to parent product IDs. Responses include each selected variant and its product summary. Product and variant details are read from `products`, so prices and stock are not copied into the wishlist. The unique user index is ensured at application startup. The wishlist stores selected variants only; obsolete product-only wishlist migration has been removed.

Responses use the standard `ApiResponse` envelope, with a Spring `Page` in `data`. Catalog routes are public and do not require a JWT.

---

### 4. Get Current User Profile (Protected - Requires JWT)
* **Method:** `GET`
* **URL:** `http://localhost:8080/api/v1/user/profile`
* **Headers:**
  * `Authorization`: `Bearer <paste_accessToken_here>`
* **Response (200 OK):**
```json
{
  "success": true,
  "message": "User profile retrieved successfully",
  "data": {
    "id": "66f01a...",
    "phoneNumber": "9876543210",
    "firstName": null,
    "lastName": null,
    "address": null,
    "role": "ROLE_CUSTOMER"
  },
  "timestamp": "2026-09-22T11:15:00"
}
```

---

### 5. Update User Profile (Protected - Requires JWT)
* **Method:** `PUT`
* **URL:** `http://localhost:8080/api/v1/user/profile`
* **Headers:**
  * `Authorization`: `Bearer <paste_accessToken_here>`
  * `Content-Type`: `application/json`
* **Body (raw JSON):**
```json
{
  "firstName": "Aashutosh",
  "lastName": "Shrivastava",
  "address": {
    "villageArea": "Flat 402, Royal Palms",
    "address2": "Opposite City Mall, MG Road",
    "cityTehsil": "Indore",
    "state": "Madhya Pradesh",
    "pincode": "452001"
  }
}
```
* **Response (200 OK):**
```json
{
  "success": true,
  "message": "User profile completed and updated successfully",
  "data": {
    "id": "66f01a...",
    "phoneNumber": "9876543210",
    "firstName": "Aashutosh",
    "lastName": "Shrivastava",
    "address": {
      "villageArea": "Flat 402, Royal Palms",
      "address2": "Opposite City Mall, MG Road",
      "cityTehsil": "Indore",
      "state": "Madhya Pradesh",
      "pincode": "452001"
    },
    "role": "ROLE_CUSTOMER",
    "isDeleted": false
  },
  "timestamp": "2026-09-22T13:50:00"
}
```

---

### 6. Delete Own Profile (Soft Delete - Protected - Requires JWT)
All 3 roles (`ROLE_CUSTOMER`, `ROLE_SELLER`, `ROLE_ADMIN`) can soft delete their own profile. The system marks `isDeleted: true` without removing database history. The last admin account cannot be soft deleted or demoted.
* **Method:** `DELETE`
* **URL:** `http://localhost:8080/api/v1/user/profile`
* **Headers:**
  * `Authorization`: `Bearer <paste_accessToken_here>`
* **Response (200 OK):**
```json
{
  "success": true,
  "message": "User profile deleted successfully",
  "data": {
    "id": "66f01a...",
    "phoneNumber": "9876543210",
    "firstName": "Aashutosh",
    "lastName": "Shrivastava",
    "address": {
      "villageArea": "Flat 402, Royal Palms",
      "address2": "Opposite City Mall, MG Road",
      "cityTehsil": "Indore",
      "state": "Madhya Pradesh",
      "pincode": "452001"
    },
    "role": "ROLE_CUSTOMER",
    "isDeleted": true
  },
  "timestamp": "2026-09-22T16:10:00"
}
```

> 🔒 **Soft-Delete Enforcement:** Once deleted:
> * Attempting to call `GET /api/v1/user/profile` or `PUT /api/v1/user/profile` returns `404 Not Found` with message: *"User profile not found or has been deleted"*.
> * Attempting to log in or verify OTP again for that phone returns `403 Forbidden` with message: *"This account has been deactivated or deleted. Please contact support."*

---

## 🛒 Cart Module API Testing Guide

All Cart endpoints require the user to be logged in via `Authorization: Bearer <accessToken>`.

### 7. Get Current User's Cart
* **Method:** `GET`
* **URL:** `http://localhost:8080/api/v1/cart`
* **Headers:** `Authorization: Bearer <accessToken>`
* **Response (200 OK):**
```json
{
  "success": true,
  "message": "Cart retrieved successfully",
  "data": {
    "id": "66f01a...",
    "userId": "66f019...",
    "phoneNumber": "9876543210",
    "items": [],
    "itemCount": 0,
    "totalQuantity": 0,
    "totalOriginalPrice": 0.0,
    "totalDiscount": 0.0,
    "totalSalePrice": 0.0,
    "totalCourierCharge": 0.0,
    "finalAmount": 0.0
  }
}
```

---

### 8. Get Lightweight Cart Badge Count
Returns only the item count and total quantity for navigation badges and mobile headers.
* **Method:** `GET`
* **URL:** `http://localhost:8080/api/v1/cart/count`
* **Headers:** `Authorization: Bearer <accessToken>`
* **Response (200 OK):**
```json
{
  "success": true,
  "message": "Cart count retrieved successfully",
  "data": {
    "itemCount": 2,
    "totalQuantity": 5
  }
}
```

---

### 9. Add Item to Cart
Adds a specific product variant by its embedded MongoDB `_id`. The request needs only the variant ID. The backend resolves its parent product; the cart stores that product ID in `items[].product` and the variant `_id` in `items[].variantId`. If that exact variant is already in the cart, its quantity is automatically incremented. The minimum quantity is one, and current stock and MRP are read from the variant.
* **Method:** `POST`
* **URL:** `http://localhost:8080/api/v1/cart/items`
* **Headers:**
  * `Authorization`: `Bearer <accessToken>`
  * `Content-Type`: `application/json`
* **Body (raw JSON):**
```json
{
  "variantId": "<variant_mongodb_id>",
  "quantity": 2
}
```
Use the chosen variant's `id` from the products API response. Cart requests accept the variant MongoDB ID only.

---

### 10. Update Item Quantity
Updates quantity directly. Set `quantity: 0` to delete the item from the cart. A positive quantity must be at least one and must not exceed available stock.
* **Method:** `PUT`
* **URL:** `http://localhost:8080/api/v1/cart/items/<variant_mongodb_id>`
* **Headers:**
  * `Authorization`: `Bearer <accessToken>`
  * `Content-Type`: `application/json`
* **Body (raw JSON):**
```json
{
  "quantity": 3
}
```

---

### 11. Remove Item from Cart
Deletes a single variation completely from the cart.
* **Method:** `DELETE`
* **URL:** `http://localhost:8080/api/v1/cart/items/<variant_mongodb_id>`
* **Headers:** `Authorization: Bearer <accessToken>`

---

### 12. Clear Cart
Empties all items at once.
* **Method:** `DELETE`
* **URL:** `http://localhost:8080/api/v1/cart`
* **Headers:** `Authorization: Bearer <accessToken>`

---

### 13. Batch Sync Cart
Synchronizes multiple cart items in batch (e.g. merging guest/offline cart after login).
* **Method:** `POST`
* **URL:** `http://localhost:8080/api/v1/cart/sync`
* **Headers:**
  * `Authorization`: `Bearer <accessToken>`
  * `Content-Type`: `application/json`
* **Body (raw JSON):**
```json
{
  "items": [
    {
      "variantId": "<variant_mongodb_id>",
      "quantity": 1
    }
  ]
}
```

---
