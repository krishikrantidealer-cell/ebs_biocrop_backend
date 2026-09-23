package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.CartItem;

public class CartItemResponse {

    private String productId;
    private String variationCode;
    private String productName;
    private String unit;
    private Integer unitQty;
    private Double price;
    private Double salePrice;
    private Double courierCharge;
    private Integer quantity;
    private Double subtotal;
    private Integer stockQty;
    private Boolean inStock;
    private Integer minOrderQty;

    public CartItemResponse() {
    }

    public CartItemResponse(String productId, String variationCode, String productName,
                            String unit, Integer unitQty, Double price, Double salePrice,
                            Double courierCharge, Integer quantity, Double subtotal,
                            Integer stockQty, Boolean inStock) {
        this(productId, variationCode, productName, unit, unitQty, price, salePrice, courierCharge, quantity, subtotal, stockQty, inStock, 1);
    }

    public CartItemResponse(String productId, String variationCode, String productName,
                            String unit, Integer unitQty, Double price, Double salePrice,
                            Double courierCharge, Integer quantity, Double subtotal,
                            Integer stockQty, Boolean inStock, Integer minOrderQty) {
        this.productId = productId;
        this.variationCode = variationCode;
        this.productName = productName;
        this.unit = unit;
        this.unitQty = unitQty;
        this.price = price;
        this.salePrice = salePrice;
        this.courierCharge = courierCharge;
        this.quantity = quantity;
        this.subtotal = subtotal;
        this.stockQty = stockQty;
        this.inStock = inStock;
        this.minOrderQty = minOrderQty != null ? minOrderQty : 1;
    }

    public static CartItemResponse fromEntity(CartItem item) {
        if (item == null) {
            return null;
        }
        return new CartItemResponse(
                item.getProductId(),
                item.getVariationCode(),
                item.getProductName(),
                item.getUnit() != null ? item.getUnit().name() : null,
                item.getUnitQty(),
                item.getPrice(),
                item.getSalePrice(),
                item.getCourierCharge(),
                item.getQuantity(),
                item.getSubtotal(),
                item.getStockQty(),
                item.getInStock(),
                item.getMinOrderQty()
        );
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

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
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
        this.minOrderQty = minOrderQty != null ? minOrderQty : 1;
    }
}
