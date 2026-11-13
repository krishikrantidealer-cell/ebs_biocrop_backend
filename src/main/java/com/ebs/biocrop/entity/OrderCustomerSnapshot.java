package com.ebs.biocrop.entity;

import org.springframework.data.mongodb.core.mapping.Field;

/** Immutable customer identity captured when the checkout is placed. */
public class OrderCustomerSnapshot {
    @Field(write = Field.Write.ALWAYS) private String firstName;
    @Field(write = Field.Write.ALWAYS) private String lastName;
    @Field(write = Field.Write.ALWAYS) private String phoneNumber;
    @Field(write = Field.Write.ALWAYS) private String email;

    public String getFirstName() { return firstName; }
    public void setFirstName(String value) { firstName = value; }
    public String getLastName() { return lastName; }
    public void setLastName(String value) { lastName = value; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String value) { phoneNumber = value; }
    public String getEmail() { return email; }
    public void setEmail(String value) { email = value; }
    @org.springframework.data.annotation.Transient public String getName() {
        return java.util.stream.Stream.of(firstName, lastName).filter(v -> v != null && !v.isBlank()).collect(java.util.stream.Collectors.joining(" "));
    }
    public void setName(String value) {
        if (value == null) { firstName = null; lastName = null; return; }
        String trimmed = value.trim();
        int separator = trimmed.indexOf(' ');
        firstName = separator < 0 ? trimmed : trimmed.substring(0, separator);
        lastName = separator < 0 ? "" : trimmed.substring(separator + 1);
    }
    @org.springframework.data.annotation.Transient public String getCompanyName() { return null; }
    public void setCompanyName(String ignored) { }
    @org.springframework.data.annotation.Transient public String getGstNumber() { return null; }
    public void setGstNumber(String ignored) { }
}
