package com.ebs.biocrop.service;

/**
 * Service for delivering SMS notifications, including OTPs.
 */
public interface SmsDeliveryService {

    /**
     * Dispatches an OTP message to the specified phone number.
     *
     * @param phoneNumber Destination mobile number (10 digits, e.g. "9876543210")
     * @param otp Plain numeric OTP code
     */
    void sendOtp(String phoneNumber, String otp);

    /**
     * Returns true if the SMS provider is configured and enabled.
     */
    boolean isConfigured();
}
