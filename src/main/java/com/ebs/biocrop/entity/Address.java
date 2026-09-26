package com.ebs.biocrop.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Address {

    private String villageArea;
    private String addressLine2;
    private String address2;
    private String cityTehsil;
    private String state;
    private String pincode;

    public Address() {
    }

    public Address(String villageArea, String cityTehsil, String state, String pincode) {
        this.villageArea = villageArea;
        this.cityTehsil = cityTehsil;
        this.state = state;
        this.pincode = pincode;
    }

    public String getVillageArea() {
        return villageArea;
    }

    public void setVillageArea(String villageArea) {
        this.villageArea = villageArea != null ? villageArea.trim() : null;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2 != null ? addressLine2.trim() : null;
    }

    public String getAddress2() {
        return address2;
    }

    public void setAddress2(String address2) {
        this.address2 = address2 != null ? address2.trim() : null;
    }

    public String getCityTehsil() {
        return cityTehsil;
    }

    public void setCityTehsil(String cityTehsil) {
        this.cityTehsil = cityTehsil != null ? cityTehsil.trim() : null;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state != null ? state.trim() : null;
    }

    public String getPincode() {
        return pincode;
    }

    public void setPincode(String pincode) {
        this.pincode = pincode != null ? pincode.trim() : null;
    }
}
