package com.ebs.biocrop.common.pagination;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Shared bounds for all offset-based list endpoints. */
public final class PageRequestSupport {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 500;

    private PageRequestSupport() { }

    public static Pageable create(int page, int size, Sort sort) {
        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);

        Sort stableSort = sort == null || sort.isUnsorted()
                ? Sort.by(Sort.Direction.ASC, "_id")
                : sort.getOrderFor("_id") == null
                    ? sort.and(Sort.by(Sort.Direction.ASC, "_id"))
                    : sort;
        return PageRequest.of(safePage, safeSize, stableSort);
    }
}
