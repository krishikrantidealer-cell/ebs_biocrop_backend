# EBS BioCrop Web Platform

Spring Boot 3.4.3 backend with MongoDB Atlas (`seller_hub`), phone-number-based passwordless OTP authentication, JWT security, profile management, cart operations, and product catalog ingestion.

---

## 📌 Tech Stack & Architecture

* **Language & Runtime:** Java 21 (LTS)
* **Framework:** Spring Boot 3.4.3 / Spring Security 6 (Stateless JWT)
* **Build Tool:** Apache Maven (`mvnw.cmd`)
* **Database:** MongoDB Atlas (Cluster: `KrishiKranti`, Database: `seller_hub`)
* **MongoDB Collections:** `users`, `carts`, `products`, `categories`, and `wishlists`.
* **Zero DB Overhead for OTP:**
  * OTPs are maintained exclusively via thread-safe in-memory cache (`ConcurrentHashMap` with TTL, 60s cooldown, 5-min expiry, max 3 attempts).
  * No OTP collection or document in MongoDB.
* **Passwordless Authentication:**
  * Absolutely no password stored or required.
  * 100% phone number + OTP verification.
* **User Roles:**
  * The `UserRole` enum includes `ROLE_CUSTOMER`, `ROLE_SELLER`, and `ROLE_ADMIN`.
  * Public OTP authentication creates customer accounts only. Seller/admin assignment is intentionally not exposed until onboarding and authorization rules are decided.
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

`ProductDataSeeder` is disabled by default. When explicitly enabled, it imports only if the `products` collection is empty; it does not replace an existing catalog. OTP requests return `503` until an SMS provider is integrated. Console OTP delivery is permitted only when the `dev` profile and `app.otp.console-delivery.enabled=true` are both set; never enable this for real users.

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
The public OTP request cannot set or change the account role. New accounts are created as `ROLE_CUSTOMER`; seller/admin onboarding and role assignment remain unimplemented until those workflows are decided.
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
    "role": "ROLE_CUSTOMER"
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

### Customer Profile Verification and Checkout

Successful OTP verification sets `isVerified` to the current profile-completeness state. `PUT /api/v1/user/profile` calculates `isProfileComplete` from nonblank first name, last name, village/area, secondary address (`address2` or `addressLine2`), city/tehsil, state, and pincode. Because profile updates require an authenticated token issued after OTP verification, `isVerified` becomes true when the profile is complete and false when it is incomplete. The profile response includes both flags. `GET /api/v1/cart/checkout-summary` returns `400 Bad Request` until `isProfileComplete` is true.

### Public Product Catalog

All catalog endpoints return only products whose status is `ACTIVE`. List, category, and search responses are paginated (`page` starts at 0; `size` defaults to 20 and is limited to 100).

* `GET /api/v1/products?page=0&size=20` — list active products.
* `GET /api/v1/products/{id}` — fetch one active product; inactive or missing products return 404.
* `GET /api/v1/products/category/{categoryId}?page=0&size=20` — filter by category id, or the legacy category name when no id matches.
* `GET /api/v1/products/search?q=insecticide&page=0&size=20` — case-insensitive literal search across title and description.
### Public Categories

* `GET /api/v1/categories` — list category documents with embedded subcategories and any third-level categories.
* `GET /api/v1/categories/{id}` — fetch one category hierarchy; an unknown ID returns 404.

Category routes are public and use the standard `ApiResponse` envelope. Category seeding from `resources/product.xlsx` is disabled by default; enable it with `--app.seed-categories=true`. Seeding only inserts when `categories` is empty.
### Customer Wishlist (JWT required)

Wishlist routes are restricted to `ROLE_CUSTOMER`. The owner is taken from the authenticated JWT; clients must not send a user ID.

* `GET /api/v1/wishlist` — return the current customer’s saved variants; a missing wishlist returns an empty list and does not create a document.
* `PUT /api/v1/wishlist/items/{variantId}` — save an active catalog variant by its embedded MongoDB `_id`. Repeating the request does not create duplicates. Out-of-stock variants may still be saved.
* `DELETE /api/v1/wishlist/items/{variantId}` — remove a specific variant; repeating the request is safe.
* `DELETE /api/v1/wishlist` — clear the current customer’s saved variants.

The `wishlists` collection stores one document per customer with a unique user ID, a `variantIds` array, and a `variantProductIds` map to its parent product IDs. Responses include each selected variant and its product summary. Product and variant details are read from `products`, so prices and stock are not copied into the wishlist. The unique user index is ensured at application startup. Legacy product-only wishlist entries migrate automatically when the product has one default variant or exactly one variant; ambiguous legacy entries remain listed in `legacyProductIdsRequiringVariantSelection` until the customer selects a variant.

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
All 3 roles (`ROLE_CUSTOMER`, `ROLE_SELLER`, `ROLE_ADMIN`) can soft delete their own profile. The system marks `isDeleted: true` without removing database history.
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
Adds a specific product variant by its embedded MongoDB `_id`. The request needs only the variant ID. The backend resolves its parent product; the cart stores that product ID in `items[].product` and the variant `_id` in `items[].variantId`. If that exact variant is already in the cart, its quantity is automatically incremented. Validates real-time inventory limits and product `minOrderQty`. Existing cart items that stored a `variationCode` are normalized to the matching variant `_id` when the cart is next accessed.
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
Use the chosen variant's `id` from the products API response. `variationCode` is a separate business code and is not accepted as the cart item identifier.

---

### 10. Update Item Quantity
Updates quantity directly. Set `quantity: 0` to delete the item from the cart. Validates product `minOrderQty`.
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

### 14. Proceed to Checkout Summary
Validates cart items against live stock, verifies the customer's delivery address, and provides a full financial breakdown.
* **Method:** `GET`
* **URL:** `http://localhost:8080/api/v1/cart/checkout-summary`
* **Headers:** `Authorization: Bearer <accessToken>`
* **Response (200 OK):**
```json
{
  "success": true,
  "message": "Checkout summary prepared successfully",
  "data": {
    "cart": { ... },
    "readyForCheckout": true,
    "validationErrors": [],
    "deliveryAddress": {
      "villageArea": "Flat 402, Royal Palms",
      "address2": "Opposite City Mall, MG Road",
      "cityTehsil": "Indore",
      "state": "Madhya Pradesh",
      "pincode": "452001"
    },
    "subtotal": 900.0,
    "totalDiscount": 100.0,
    "shippingCharge": 40.0,
    "finalPayableAmount": 940.0
  }
}
```
