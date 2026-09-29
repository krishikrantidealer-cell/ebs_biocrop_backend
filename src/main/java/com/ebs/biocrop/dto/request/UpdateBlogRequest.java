package com.ebs.biocrop.dto.request;

import com.ebs.biocrop.entity.Blog;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Full replacement of editable blog content and display metadata. */
public class UpdateBlogRequest {
    @NotBlank @Size(max = 200)
    private String title;
    @NotBlank @Size(max = 200)
    private String slug;
    @NotBlank @Size(max = 300)
    private String excerpt;
    private Blog.BlogContent content;
    private Blog.BlogCategory category;
    @Size(max = 10)
    private List<String> tags;
    private Blog.FeaturedImage featuredImage;
    private Blog.BlogSeo seo;
    @Size(max = 8)
    private List<String> relatedBlogIds;
    @Size(max = 10)
    private List<String> relatedProductIds;
    private Boolean isFeatured;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getExcerpt() { return excerpt; }
    public void setExcerpt(String excerpt) { this.excerpt = excerpt; }
    public Blog.BlogContent getContent() { return content; }
    public void setContent(Blog.BlogContent content) { this.content = content; }
    public Blog.BlogCategory getCategory() { return category; }
    public void setCategory(Blog.BlogCategory category) { this.category = category; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public Blog.FeaturedImage getFeaturedImage() { return featuredImage; }
    public void setFeaturedImage(Blog.FeaturedImage featuredImage) { this.featuredImage = featuredImage; }
    public Blog.BlogSeo getSeo() { return seo; }
    public void setSeo(Blog.BlogSeo seo) { this.seo = seo; }
    public List<String> getRelatedBlogIds() { return relatedBlogIds; }
    public void setRelatedBlogIds(List<String> relatedBlogIds) { this.relatedBlogIds = relatedBlogIds; }
    public List<String> getRelatedProductIds() { return relatedProductIds; }
    public void setRelatedProductIds(List<String> relatedProductIds) { this.relatedProductIds = relatedProductIds; }
    public Boolean getIsFeatured() { return isFeatured; }
    public void setIsFeatured(Boolean featured) { isFeatured = featured; }
}
