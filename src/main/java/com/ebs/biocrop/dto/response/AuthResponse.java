package com.ebs.biocrop.dto.response;

public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private long expiresInMs;
    private String userId;
    private String phoneNumber;
    private String role;
    private boolean isProfileComplete;

    public AuthResponse() {
    }

    public AuthResponse(String accessToken, String refreshToken, long expiresInMs, String userId, String phoneNumber, String role) {
        this(accessToken, refreshToken, expiresInMs, userId, phoneNumber, role, false);
    }

    public AuthResponse(String accessToken, String refreshToken, long expiresInMs, String userId, String phoneNumber,
                        String role, boolean isProfileComplete) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenType = "Bearer";
        this.expiresInMs = expiresInMs;
        this.userId = userId;
        this.phoneNumber = phoneNumber;
        this.role = role;
        this.isProfileComplete = isProfileComplete;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public long getExpiresInMs() {
        return expiresInMs;
    }

    public void setExpiresInMs(long expiresInMs) {
        this.expiresInMs = expiresInMs;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean getIsProfileComplete() { return isProfileComplete; }
    public void setIsProfileComplete(boolean profileComplete) { isProfileComplete = profileComplete; }
}
