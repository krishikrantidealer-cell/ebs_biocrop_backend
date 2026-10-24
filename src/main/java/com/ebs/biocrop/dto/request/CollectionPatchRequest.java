package com.ebs.biocrop.dto.request;

import com.ebs.biocrop.dto.request.CollectionWriteRequest.SubCollectionInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Nullable fields are left unchanged; an empty subCollections list removes every subcollection. */
public class CollectionPatchRequest {
    @Pattern(regexp = ".*\\S.*", message = "Collection name cannot be blank") @Size(max = 120) private String name;
    @Size(max = 140) private String slug;
    @Size(max = 2000) private String description;
    private Boolean isActive;
    @Min(0) private Integer priority;
    @Size(max = 100) private List<@Valid SubCollectionInput> subCollections;
    @Size(max = 160) private String bannerTitle;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public List<SubCollectionInput> getSubCollections() { return subCollections; }
    public void setSubCollections(List<SubCollectionInput> subCollections) { this.subCollections = subCollections; }
    public String getBannerTitle() { return bannerTitle; }
    public void setBannerTitle(String bannerTitle) { this.bannerTitle = bannerTitle; }

    public boolean hasChanges() {
        return name != null || slug != null || description != null || isActive != null
                || priority != null || subCollections != null || bannerTitle != null;
    }
}
