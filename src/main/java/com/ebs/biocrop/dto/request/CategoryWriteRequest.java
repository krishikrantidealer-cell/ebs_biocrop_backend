package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CategoryWriteRequest {
    @NotBlank(message = "Category name is required")
    @Size(max = 100, message = "Category name must be at most 100 characters")
    private String name;

    @Size(max = 120, message = "Category slug must be at most 120 characters")
    private String slug;

    @Size(max = 64, message = "Parent category ID must be at most 64 characters")
    private String parentId;

    @Min(value = 0, message = "Sort order must be zero or greater")
    private Integer sortOrder;

    private Boolean isActive;

    @Size(max = 2048, message = "Banner image URL must be at most 2048 characters")
    private String bannerImage;

    @Size(max = 2048, message = "Catalogue PDF URL must be at most 2048 characters")
    private String cataloguePdf;

    @Size(max = 200, message = "Banner title must be at most 200 characters")
    private String bannerTitle;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }
    public String getBannerImage() { return bannerImage; }
    public void setBannerImage(String bannerImage) { this.bannerImage = bannerImage; }
    public String getCataloguePdf() { return cataloguePdf; }
    public void setCataloguePdf(String cataloguePdf) { this.cataloguePdf = cataloguePdf; }
    public String getBannerTitle() { return bannerTitle; }
    public void setBannerTitle(String bannerTitle) { this.bannerTitle = bannerTitle; }
}
