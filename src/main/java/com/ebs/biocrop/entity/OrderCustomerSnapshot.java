package com.ebs.biocrop.entity;

public class OrderCustomerSnapshot {
    private String name;
    private String phoneNumber;
    private String companyName;
    private String gstNumber;

    public OrderCustomerSnapshot() { }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getGstNumber() { return gstNumber; }
    public void setGstNumber(String gstNumber) { this.gstNumber = gstNumber; }
}
