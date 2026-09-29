package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Blog;
import com.ebs.biocrop.entity.enums.BlogStatus;

import java.time.Instant;

public class BlogDraftResponse {

    private final String id;
    private final String title;
    private final String slug;
    private final String excerpt;
    private final BlogStatus status;
    private final Instant createdAt;

    public BlogDraftResponse(Blog blog) {
        this.id = blog.getId();
        this.title = blog.getTitle();
        this.slug = blog.getSlug();
        this.excerpt = blog.getExcerpt();
        this.status = blog.getStatus();
        this.createdAt = blog.getCreatedAt();
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getSlug() { return slug; }
    public String getExcerpt() { return excerpt; }
    public BlogStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
