package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.CreateBlogDraftRequest;
import com.ebs.biocrop.dto.request.ScheduleBlogRequest;
import com.ebs.biocrop.dto.request.UpdateBlogRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.BlogAdminResponse;
import com.ebs.biocrop.dto.response.BlogDraftResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.entity.Blog;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.BlogService;
import com.ebs.biocrop.service.RateLimitGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/admin/blogs")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin blogs", description = "Admin blog publishing and moderation APIs.")
@SecurityRequirement(name = "bearerAuth")
public class AdminBlogController {

    private final BlogService blogService;
    private final RateLimitGuard rateLimitGuard;

    public AdminBlogController(BlogService blogService, RateLimitGuard rateLimitGuard) {
        this.blogService = blogService;
        this.rateLimitGuard = rateLimitGuard;
    }

    // Create a new blog draft
    @PostMapping
    @Operation(summary = "Create a blog draft")
    public ResponseEntity<ApiResponse<BlogDraftResponse>> createBlogDraft(
            Authentication authentication,
            @Valid @RequestBody CreateBlogDraftRequest request) {
        ObjectId adminId = rateLimitedAdminId(authentication);
        BlogDraftResponse draft = blogService.createDraft(request, adminId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Blog draft created successfully", draft));
    }

    // List admin-managed blogs with optional status filter
    @GetMapping
    @Operation(summary = "List admin-managed blogs")
    public ResponseEntity<ApiResponse<PagedResponse<BlogAdminResponse>>> getBlogs(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Blog> blogs = blogService.listAdminBlogs(status, page, size);
        return ResponseEntity.ok(ApiResponse.ok("Admin blogs retrieved successfully",
                PagedResponse.from(blogs, BlogAdminResponse::from)));
    }

    // Get an admin-managed blog by ID
    @GetMapping("/{blogId}")
    @Operation(summary = "Get an admin-managed blog by ID")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> getBlogById(@PathVariable String blogId) {
        return ResponseEntity.ok(ApiResponse.ok("Blog retrieved successfully",
                BlogAdminResponse.from(blogService.getAdminBlog(blogId))));
    }

    // Update an admin-managed blog
    @PutMapping("/{blogId}")
    @Operation(summary = "Replace an admin-managed blog")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> updateBlog(
            Authentication authentication,
            @PathVariable String blogId,
            @Valid @RequestBody UpdateBlogRequest request) {
        ObjectId adminId = rateLimitedAdminId(authentication);
        return ResponseEntity.ok(ApiResponse.ok("Blog updated successfully",
                BlogAdminResponse.from(blogService.updateBlog(blogId, request, adminId))));
    }

    // Publish a blog immediately
    @PostMapping("/{blogId}/publish")
    @Operation(summary = "Publish a blog immediately")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> publishBlog(
            Authentication authentication, @PathVariable String blogId) {
        ObjectId adminId = rateLimitedAdminId(authentication);
        return ResponseEntity.ok(ApiResponse.ok("Blog published successfully",
                BlogAdminResponse.from(blogService.publishNow(blogId, adminId))));
    }

    // Schedule a blog for future publication
    @PostMapping("/{blogId}/schedule")
    @Operation(summary = "Schedule a blog for publication")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> scheduleBlog(
            Authentication authentication,
            @PathVariable String blogId,
            @Valid @RequestBody ScheduleBlogRequest request) {
        ObjectId adminId = rateLimitedAdminId(authentication);
        return ResponseEntity.ok(ApiResponse.ok("Blog scheduled successfully",
                BlogAdminResponse.from(blogService.schedule(blogId, request.getScheduledAt(), adminId))));
    }

    // Unpublish a blog
    @PostMapping("/{blogId}/unpublish")
    @Operation(summary = "Unpublish a blog")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> unpublishBlog(
            Authentication authentication, @PathVariable String blogId) {
        ObjectId adminId = rateLimitedAdminId(authentication);
        return ResponseEntity.ok(ApiResponse.ok("Blog unpublished successfully",
                BlogAdminResponse.from(blogService.unpublish(blogId, adminId))));
    }

    // Archive a blog
    @PostMapping("/{blogId}/archive")
    @Operation(summary = "Archive a blog")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> archiveBlog(
            Authentication authentication, @PathVariable String blogId) {
        ObjectId adminId = rateLimitedAdminId(authentication);
        return ResponseEntity.ok(ApiResponse.ok("Blog archived successfully",
                BlogAdminResponse.from(blogService.archive(blogId, adminId))));
    }


    // Restore an archived blog as a draft
    @PostMapping("/{blogId}/restore")
    @Operation(summary = "Restore an archived blog as a draft")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> restoreBlog(
            Authentication authentication, @PathVariable String blogId) {
        ObjectId adminId = rateLimitedAdminId(authentication);
        return ResponseEntity.ok(ApiResponse.ok("Archived blog restored as a draft",
                BlogAdminResponse.from(blogService.restore(blogId, adminId))));
    }

    // Restore a soft-deleted blog as a draft
    @PostMapping("/{blogId}/restore-deleted")
    @Operation(summary = "Restore a soft-deleted blog as a draft")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> restoreDeletedBlog(
            Authentication authentication, @PathVariable String blogId) {
        ObjectId adminId = rateLimitedAdminId(authentication);
        return ResponseEntity.ok(ApiResponse.ok("Soft-deleted blog restored as a draft",
                BlogAdminResponse.from(blogService.restoreSoftDeleted(blogId, adminId))));
    }

    // Soft-delete a blog
    @DeleteMapping("/{blogId}")
    @Operation(summary = "Soft-delete a blog")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> softDeleteBlog(
            Authentication authentication, @PathVariable String blogId) {
        ObjectId adminId = rateLimitedAdminId(authentication);
        return ResponseEntity.ok(ApiResponse.ok("Blog soft-deleted successfully",
                BlogAdminResponse.from(blogService.softDelete(blogId, adminId))));
    }

    // Permanently delete a blog
    private ObjectId rateLimitedAdminId(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof CustomUserDetails user)
                || user.getRole() != UserRole.ROLE_ADMIN
                || !ObjectId.isValid(user.getId())) {
            throw new AppException("Admin access is required", HttpStatus.FORBIDDEN);
        }
        rateLimitGuard.enforce("admin-blog-write", user.getId(), 60, Duration.ofMinutes(1));
        return new ObjectId(user.getId());
    }
}
