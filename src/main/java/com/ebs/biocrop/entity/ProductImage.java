package com.ebs.biocrop.entity;


public class ProductImage {

    private String id;
    private String originalObjectName;
    private String midObjectName;
    private String lowObjectName;
    private Integer displayOrder;


    public ProductImage() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOriginalObjectName() {
        return originalObjectName;
    }

    public void setOriginalObjectName(String originalObjectName) {
        this.originalObjectName = originalObjectName;
    }

    public String getMidObjectName() {
        return midObjectName;
    }

    public void setMidObjectName(String midObjectName) {
        this.midObjectName = midObjectName;
    }

    public String getLowObjectName() {
        return lowObjectName;
    }

    public void setLowObjectName(String lowObjectName) {
        this.lowObjectName = lowObjectName;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }
}