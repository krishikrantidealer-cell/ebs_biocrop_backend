package com.ebs.biocrop.common.pagination;

import com.ebs.biocrop.exception.AppException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

/** Shared bounds for all offset-based list endpoints. */
public final class PageRequestSupport {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private PageRequestSupport() { }

    public static Pageable create(int page, int size, Sort sort) {
        if (page < 0) {
            throw new AppException("Page must be zero or greater", HttpStatus.BAD_REQUEST);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new AppException("Page size must be between 1 and " + MAX_SIZE, HttpStatus.BAD_REQUEST);
        }
        Sort stableSort = sort == null || sort.isUnsorted()
                ? Sort.by(Sort.Direction.ASC, "_id")
                : sort.getOrderFor("_id") == null
                    ? sort.and(Sort.by(Sort.Direction.ASC, "_id"))
                    : sort;
        return PageRequest.of(page, size, stableSort);
    }
}
