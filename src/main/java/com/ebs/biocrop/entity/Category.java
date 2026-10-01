package com.ebs.biocrop.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A category node. Roots and descendants are all stored as documents in `categories`. */
@Document(collection = "categories")
@CompoundIndexes({
        @CompoundIndex(name = "category_parent_sort_idx", def = "{ 'parentId': 1, 'sortOrder': 1 }"),
        @CompoundIndex(name = "category_parent_slug_unique_idx", def = "{ 'parentId': 1, 'slug': 1 }", unique = true)
})
public class Category {

    @Id
    private String id;

    private String name;
    private String slug;
    private String parentId;
    @org.springframework.data.mongodb.core.index.Indexed(name = "category_level_idx")
    private Integer level;
    private Integer sortOrder = 0;
    private Boolean isActive = true;
    private Instant createdAt;
    private Instant updatedAt;

    // Optional merchandising assets retained from the existing category API.
    private String bannerImage;
    private String cataloguePdf;
    private String iconImage;
    private String bannerTitle;

    public Category() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getBannerImage() { return bannerImage; }
    public void setBannerImage(String bannerImage) { this.bannerImage = bannerImage; }
    public String getCataloguePdf() { return cataloguePdf; }
    public void setCataloguePdf(String cataloguePdf) { this.cataloguePdf = cataloguePdf; }
    public String getIconImage() { return iconImage; }
    public void setIconImage(String iconImage) { this.iconImage = iconImage; }
    public String getBannerTitle() { return bannerTitle; }
    public void setBannerTitle(String bannerTitle) { this.bannerTitle = bannerTitle; }
}
