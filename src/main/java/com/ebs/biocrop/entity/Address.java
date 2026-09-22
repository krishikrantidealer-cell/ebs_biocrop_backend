package com.ebs.biocrop.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import org.springframework.data.mongodb.core.mapping.Field;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Address {

    private String line1;
    private String street;
    private String city;
    private String state;

    @Field("pin_code")
    @JsonProperty("pin_code")
    private String pinCode;

    public Address() {
    }

    public Address(String line1, String street, String city, String state, String pinCode) {
        this.line1 = line1;
        this.street = street;
        this.city = city;
        this.state = state;
        this.pinCode = pinCode;
    }

    public String getLine1() {
        return line1;
    }

    public void setLine1(String line1) {
        this.line1 = line1 != null ? line1.trim() : null;
    }

    public String getStreet() {
        return street;
    }

    public void setStreet(String street) {
        this.street = street != null ? street.trim() : null;
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

    @JsonProperty("pin_code")
    public String getPinCode() {
        return pinCode;
    }

    @JsonProperty("pin_code")
    public void setPinCode(String pinCode) {
        this.pinCode = pinCode != null ? pinCode.trim() : null;
    }

    @JsonSetter("pinCode")
    public void setPinCodeCamelCase(String pinCode) {
        if (this.pinCode == null) {
            this.pinCode = pinCode != null ? pinCode.trim() : null;
        }
    }
}
