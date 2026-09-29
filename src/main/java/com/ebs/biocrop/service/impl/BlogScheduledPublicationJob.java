package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.service.BlogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BlogScheduledPublicationJob {

    private static final Logger log = LoggerFactory.getLogger(BlogScheduledPublicationJob.class);
    private final BlogService blogService;

    public BlogScheduledPublicationJob(BlogService blogService) {
        this.blogService = blogService;
    }

    @Scheduled(fixedDelayString = "${app.blogs.scheduler-interval-ms:30000}")
    public void publishDueBlogs() {
        try {
            blogService.publishDueScheduledBlogs();
        } catch (RuntimeException exception) {
            log.error("Scheduled blog publication pass failed", exception);
        }
    }
}
