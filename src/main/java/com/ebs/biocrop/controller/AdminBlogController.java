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
import jakarta.validation.Valid;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/blogs")
public class AdminBlogController {

    private final BlogService blogService;

    public AdminBlogController(BlogService blogService) {
        this.blogService = blogService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BlogDraftResponse>> createDraft(
            Authentication authentication,
            @Valid @RequestBody CreateBlogDraftRequest request) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof CustomUserDetails user)
                || user.getRole() != UserRole.ROLE_ADMIN) {
            throw new AppException("Admin access is required", HttpStatus.FORBIDDEN);
        }
        if (!ObjectId.isValid(user.getId())) {
            throw new AppException("Authenticated admin identity is invalid", HttpStatus.FORBIDDEN);
        }

        BlogDraftResponse draft = blogService.createDraft(request, new ObjectId(user.getId()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Blog draft created successfully", draft));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<BlogAdminResponse>>> listBlogs(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Blog> blogs = blogService.listAdminBlogs(status, page, size);
        return ResponseEntity.ok(ApiResponse.ok("Admin blogs retrieved successfully",
                PagedResponse.from(blogs, BlogAdminResponse::from)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> getBlog(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("Blog retrieved successfully",
                BlogAdminResponse.from(blogService.getAdminBlog(id))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> updateBlog(
            Authentication authentication,
            @PathVariable String id,
            @Valid @RequestBody UpdateBlogRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Blog updated successfully",
                BlogAdminResponse.from(blogService.updateBlog(id, request, adminId(authentication)))));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> publish(
            Authentication authentication, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("Blog published successfully",
                BlogAdminResponse.from(blogService.publishNow(id, adminId(authentication)))));
    }

    @PostMapping("/{id}/schedule")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> schedule(
            Authentication authentication,
            @PathVariable String id,
            @Valid @RequestBody ScheduleBlogRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Blog scheduled successfully",
                BlogAdminResponse.from(blogService.schedule(id, request.getScheduledAt(), adminId(authentication)))));
    }

    @PostMapping("/{id}/unpublish")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> unpublish(
            Authentication authentication, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("Blog unpublished successfully",
                BlogAdminResponse.from(blogService.unpublish(id, adminId(authentication)))));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> archive(
            Authentication authentication, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("Blog archived successfully",
                BlogAdminResponse.from(blogService.archive(id, adminId(authentication)))));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> restore(
            Authentication authentication, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("Archived blog restored as a draft",
                BlogAdminResponse.from(blogService.restore(id, adminId(authentication)))));
    }

    @PostMapping("/{id}/restore-deleted")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> restoreSoftDeleted(
            Authentication authentication, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("Soft-deleted blog restored as a draft",
                BlogAdminResponse.from(blogService.restoreSoftDeleted(id, adminId(authentication)))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<BlogAdminResponse>> delete(
            Authentication authentication, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("Blog soft-deleted successfully",
                BlogAdminResponse.from(blogService.softDelete(id, adminId(authentication)))));
    }

    private ObjectId adminId(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof CustomUserDetails user)
                || user.getRole() != UserRole.ROLE_ADMIN
                || !ObjectId.isValid(user.getId())) {
            throw new AppException("Admin access is required", HttpStatus.FORBIDDEN);
        }
        return new ObjectId(user.getId());
    }
}
