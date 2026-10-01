# Render test deployment

This deployment is for temporary frontend integration and API testing. The blueprint uses the `dev` Spring profile so test phone/email OTPs and password-reset links are written to Render logs. Do not use this profile with real users or production credentials.

## Render Blueprint setup

1. Push the repository to GitHub and create a Render Blueprint from `render.yaml`.
2. Supply these values in Render's service environment:

   | Variable | Value |
   | --- | --- |
   | `MONGODB_URI` | Atlas connection URI; keep credentials private |
   | `MONGODB_DATABASE` | Prefer a staging database containing the approved six collections and test records |
   | `JWT_SECRET` | A new random secret with at least 32 bytes of key material |
   | `CORS_ALLOWED_ORIGINS` | Exact frontend origins, comma-separated, such as `https://frontend.example.com,http://localhost:5173` |

   Render stores these in its environment settings. Never commit `.env`, credentials, or a real JWT secret.

3. Add the Render service's outbound IP ranges to the MongoDB Atlas project access list. Find the ranges on the service's **Connect → Outbound** panel. Do not open Atlas access to every IP address for convenience.
4. Deploy and confirm the Render health check succeeds at `/api/v1/health`.
5. Set the frontend's API base URL to the Render `onrender.com` URL. Open `/swagger-ui/index.html` to inspect the current API contract.

## Test authentication on Render

The `dev` profile enables console delivery for the current OTP implementations. After calling a send-OTP or forgot-password endpoint, use Render's **Logs** panel to retrieve the test OTP or reset link. Render's free web service cannot send outbound SMTP on ports 25, 465, or 587, so these logs are for test accounts only.

Use the provisioned seller/admin accounts and customer phone numbers in the staging database. Do not put real customer data in a public test environment.

## Free-tier behavior

The free service can sleep after inactivity and may restart; the first request after sleep can be slow. Plan for cold starts during frontend testing. No keep-alive monitor is configured. Monitor Render's usage dashboard because free-tier limits and included quotas can change.

## Configuration notes

The container listens on Render's `PORT` value. JVM memory options are set in `Dockerfile` for the free instance size. The API is stateless; uploaded files and local filesystem data are not persisted. Keep database state in MongoDB Atlas.
