package com.ebs.biocrop.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "variant_labels")
public class VariantLabel {

    @Id
    private String id;

    private String label;

    @Indexed(unique = true)
    private String normalizedLabel;

    private double packSize;
    private String unit;
    private int packQuantity = 1;

    @CreatedDate
    private Instant createdAt;

    public VariantLabel() {
    }

    public VariantLabel(String label, String normalizedLabel) {
        this(label, normalizedLabel, 0.0, "", 1);
    }

    public VariantLabel(String label, String normalizedLabel, double packSize, String unit, int packQuantity) {
        this.label = label;
        this.normalizedLabel = normalizedLabel;
        this.packSize = packSize;
        this.unit = unit;
        this.packQuantity = packQuantity > 0 ? packQuantity : 1;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getNormalizedLabel() {
        return normalizedLabel;
    }

    public void setNormalizedLabel(String normalizedLabel) {
        this.normalizedLabel = normalizedLabel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public double getPackSize() {
        return packSize;
    }

    public void setPackSize(double packSize) {
        this.packSize = packSize;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public int getPackQuantity() {
        return packQuantity;
    }

    public void setPackQuantity(int packQuantity) {
        this.packQuantity = packQuantity;
    }
}
