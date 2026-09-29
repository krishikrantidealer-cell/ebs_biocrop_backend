package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.request.CreateBlogDraftRequest;
import com.ebs.biocrop.dto.request.UpdateBlogRequest;
import com.ebs.biocrop.entity.Blog;
import com.ebs.biocrop.dto.response.BlogFacetResponse;
import com.ebs.biocrop.dto.response.BlogDraftResponse;
import org.springframework.data.domain.Page;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.List;

public interface BlogService {
    BlogDraftResponse createDraft(CreateBlogDraftRequest request, ObjectId adminId);
    Blog getAdminBlog(String id);
    Page<Blog> listAdminBlogs(String status, int page, int size);
    Blog updateBlog(String id, UpdateBlogRequest request, ObjectId adminId);
    Blog publishNow(String id, ObjectId adminId);
    Blog schedule(String id, Instant scheduledAt, ObjectId adminId);
    Blog unpublish(String id, ObjectId adminId);
    Blog archive(String id, ObjectId adminId);
    Blog restore(String id, ObjectId adminId);
    Blog restoreSoftDeleted(String id, ObjectId adminId);
    Blog softDelete(String id, ObjectId adminId);
    Page<Blog> listPublicBlogs(String categorySlug, String tag, Boolean featured, int page, int size);
    Blog getPublicBlog(String slug);
    List<BlogFacetResponse> publicCategories();
    List<BlogFacetResponse> publicTags();
    void publishDueScheduledBlogs();
}
