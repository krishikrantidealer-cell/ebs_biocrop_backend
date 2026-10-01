# API redesign review

This review compares the supplied Postman collection with the current backend controllers. It is a route/schema review only; no requests were sent and no database migration was run.

## Existing customer APIs

| Area | Current result | Follow-up |
| --- | --- | --- |
| Health, OTP login/refresh, profile | Routes still exist. OTP login assigns the stored role or defaults new accounts to `ROLE_CUSTOMER`; client-supplied role is not trusted. | Update the collection to remove `role` from OTP verification and use role-specific tokens provisioned by an admin. |
| Public products, details, category search | Routes still exist with the current product shape. | The 83 obsolete workbook records have been removed; keep product requests as examples until the approved replacement catalog arrives. |
| Cart and wishlist | Routes still exist. | Correct the wishlist delete URL to `/api/v1/wishlist/items/{{variantId}}`. Existing order/payment work remains out of scope. |
| Categories | Public endpoints now return flat category documents with `parentId` and `level`, not a nested hierarchy response. | Query roots with `level=0`, then children using `parentId`. |
| Blog | Existing public and admin operations remain. | Fill in the two blank Postman requests: retrieve with `GET /admin/blogs/{{blogId}}`; edit with `PUT /admin/blogs/{{blogId}}` and a full update body. |

## New/current operational APIs to include

- Admin category maintenance: `GET`, `POST`, `PUT /{id}`, and `DELETE /{id}` under `/api/v1/admin/categories`.
- Admin role assignment: `PATCH /api/v1/admin/users/{userId}/role`. This is the controlled path for promoting an OTP-authenticated phone account to `ROLE_SELLER` or `ROLE_ADMIN`.
- Seller listing workflow: `POST`, `GET`, and `PUT /api/v1/seller/products`; submitted listings are pending review and scoped to the authenticated seller.
- Admin listing review: `GET /api/v1/admin/products?status=PENDING_REVIEW` and `PATCH /api/v1/admin/products/{id}/review?approved=true`.
- Admin availability toggle: `PATCH /api/v1/admin/products/{id}/availability?available=true|false`, available only for already-approved products.

Category depth is capped at levels 0–2 in one `categories` collection, using `parentId` and `level`. Category migration preflights embedded data and stops before changing documents if it finds deeper nesting. Public product endpoints show only available, approved seller-owned listings. The 83 obsolete workbook product documents were removed from the existing `products` collection.

GST percent is retained per product variant for later tax policy decisions. Product dimensions are stored in centimeters, with optional variant-level overrides. Seller business verification, certificate upload/storage, and shipping calculation remain later work as previously agreed. Cart checkout and shipping/fare calculations are outside the current scope; cart add/update/remove remains available.

## Collection safety and test setup

The supplied collection contains static authorization values and assumes seeded product/category ids. The revised collection uses Postman variables for tokens, ids, and test data, and has no static bearer token. Keep separate customer, seller, and admin accounts/tokens; public OTP verification does not register or grant roles. Seed/provision an admin directly through the agreed operational process before exercising admin requests.

The revised collection is a request template, not a live test run. Product and listing payloads are examples only and must be replaced with approved catalog data when it becomes available.
