package com.ebs.biocrop.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.mongodb.core.mapping.Field;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Address {

    @Field("address_line_1")
    @JsonProperty("address_line_1")
    private String addressLine1;

    @Field("near_by_location")
    @JsonProperty("near_by_location")
    private String nearbyLocation;

    private String city;
    private String state;

    @Field("pin_code")
    @JsonProperty("pin_code")
    private String pinCode;

    public Address() {
    }

    public Address(String addressLine1, String nearbyLocation, String city, String state, String pinCode) {
        this.addressLine1 = addressLine1;
        this.nearbyLocation = nearbyLocation;
        this.city = city;
        this.state = state;
        this.pinCode = pinCode;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1 != null ? addressLine1.trim() : null;
    }

    public String getNearbyLocation() {
        return nearbyLocation;
    }

    public void setNearbyLocation(String nearbyLocation) {
        this.nearbyLocation = nearbyLocation != null ? nearbyLocation.trim() : null;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city != null ? city.trim() : null;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state != null ? state.trim() : null;
    }

    public String getPinCode() {
        return pinCode;
    }

    public void setPinCode(String pinCode) {
        this.pinCode = pinCode != null ? pinCode.trim() : null;
    }
}
