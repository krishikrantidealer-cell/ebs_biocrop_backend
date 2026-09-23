package com.ebs.biocrop.entity;

import com.ebs.biocrop.entity.enums.ProductUnit;

public class CartItem {

    private String productId;
    private String variationCode;
    private String productName;
    private ProductUnit unit;
    private Integer unitQty;
    private Double price;
    private Double salePrice;
    private Double courierCharge;
    private Integer quantity;
    private Double subtotal;
    private Integer stockQty;
    private Boolean inStock;
    private Integer minOrderQty = 1;

    public CartItem() {
    }

    public CartItem(String productId, String variationCode, String productName,
                    ProductUnit unit, Integer unitQty, Double price, Double salePrice,
                    Double courierCharge, Integer quantity, Integer stockQty, Boolean inStock) {
        this(productId, variationCode, productName, unit, unitQty, price, salePrice, courierCharge, quantity, stockQty, inStock, 1);
    }

    public CartItem(String productId, String variationCode, String productName,
                    ProductUnit unit, Integer unitQty, Double price, Double salePrice,
                    Double courierCharge, Integer quantity, Integer stockQty, Boolean inStock, Integer minOrderQty) {
        this.productId = productId;
        this.variationCode = variationCode;
        this.productName = productName;
        this.unit = unit;
        this.unitQty = unitQty;
        this.price = price != null ? price : 0.0;
        this.salePrice = salePrice != null ? salePrice : 0.0;
        this.courierCharge = courierCharge != null ? courierCharge : 0.0;
        this.quantity = quantity != null ? quantity : 1;
        this.stockQty = stockQty != null ? stockQty : 0;
        this.inStock = inStock != null ? inStock : true;
        this.minOrderQty = minOrderQty != null && minOrderQty > 0 ? minOrderQty : 1;
        this.subtotal = this.salePrice * this.quantity;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getVariationCode() {
        return variationCode;
    }

    public void setVariationCode(String variationCode) {
        this.variationCode = variationCode;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public ProductUnit getUnit() {
        return unit;
    }

    public void setUnit(ProductUnit unit) {
        this.unit = unit;
    }

    public Integer getUnitQty() {
        return unitQty;
    }

    public void setUnitQty(Integer unitQty) {
        this.unitQty = unitQty;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public Double getSalePrice() {
        return salePrice;
    }

    public void setSalePrice(Double salePrice) {
        this.salePrice = salePrice;
    }

    public Double getCourierCharge() {
        return courierCharge;
    }

    public void setCourierCharge(Double courierCharge) {
        this.courierCharge = courierCharge;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
        if (this.salePrice != null && this.quantity != null) {
            this.subtotal = this.salePrice * this.quantity;
        }
    }

    public Double getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(Double subtotal) {
        this.subtotal = subtotal;
    }

    public Integer getStockQty() {
        return stockQty;
    }

    public void setStockQty(Integer stockQty) {
        this.stockQty = stockQty;
    }

    public Boolean getInStock() {
        return inStock;
    }

    public void setInStock(Boolean inStock) {
        this.inStock = inStock;
    }

    public Integer getMinOrderQty() {
        return minOrderQty != null ? minOrderQty : 1;
    }

    public void setMinOrderQty(Integer minOrderQty) {
        this.minOrderQty = minOrderQty != null && minOrderQty > 0 ? minOrderQty : 1;
    }
}
