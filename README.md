# EBS BioCrop Web Platform

Production-ready Spring Boot 3.4.3 backend with MongoDB Atlas (`seller_hub`), phone-number-based passwordless OTP authentication (zero DB storage overhead), role-based access control, and complete product catalog ingestion.

---

## 📌 Tech Stack & Architecture

* **Language & Runtime:** Java 21 (LTS)
* **Framework:** Spring Boot 3.4.3 / Spring Security 6 (Stateless JWT)
* **Build Tool:** Apache Maven (`mvnw.cmd`)
* **Database:** MongoDB Atlas (Cluster: `KrishiKranti`, Database: `seller_hub`)
* **MongoDB Collections:** Strictly two collections:
  1. `users`: Stores user accounts (Customer, Seller, Admin).
  2. `products`: Stores 540 enterprise agricultural product variations imported from `product.xlsx`.
* **Zero DB Overhead for OTP:**
  * OTPs are maintained exclusively via thread-safe in-memory cache (`ConcurrentHashMap` with TTL, 60s cooldown, 5-min expiry, max 3 attempts).
  * No OTP collection or document in MongoDB.
* **Passwordless Authentication:**
  * Absolutely no password stored or required.
  * 100% phone number + OTP verification.
* **User Roles:**
  * Supported roles via `UserRole` enum: `ROLE_CUSTOMER`, `ROLE_SELLER`, `ROLE_ADMIN`.
  * For initial phase, all 3 roles share identical user attributes: Full Name, Phone Number, Address, Role.
* **User Schema (`users` collection):**
  * `id` (`String` / MongoDB ObjectId)
  * `phoneNumber` (`String`, unique indexed)
  * `fullName` (`String`, captured upon onboarding/profile completion)
  * `address` (`String`, delivery/business address)
  * `role` (`UserRole`: `ROLE_CUSTOMER`, `ROLE_SELLER`, `ROLE_ADMIN`)
  * `createdAt` (`LocalDateTime`)
  * `updatedAt` (`LocalDateTime`)

---

## 🌿 Git Branch Policy

> **Branch:** `feature/aashutosh-shrivastava`  
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
The server will start on port `8080` (`http://localhost:8080`).  
On startup, `ProductDataSeeder` automatically ensures all 540 products from `resources/product.xlsx` (and `resources/products.json`) are synchronized into MongoDB Atlas `seller_hub.products`.

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
> 💡 *Note for Testing:* In local development mode, the generated OTP is logged directly to the console:
> ```text
> 🔐 [DEV/TEST] GENERATED OTP FOR PHONE NUMBER [9876543210]: [123456]
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
    "fullName": null,
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
  "fullName": "Aashutosh Shrivastava",
  "address": {
    "line1": "Flat 402, Royal Palms",
    "street": "MG Road",
    "city": "Indore",
    "state": "Madhya Pradesh",
    "pin_code": "452001"
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
    "fullName": "Aashutosh Shrivastava",
    "address": {
      "line1": "Flat 402, Royal Palms",
      "street": "MG Road",
      "city": "Indore",
      "state": "Madhya Pradesh",
      "pin_code": "452001"
    },
    "role": "ROLE_CUSTOMER"
  },
  "timestamp": "2026-09-22T13:10:00"
}
```
