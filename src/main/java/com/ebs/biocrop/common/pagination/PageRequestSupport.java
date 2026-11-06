package com.ebs.biocrop.common.pagination;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Shared bounds for all offset-based list endpoints. */
public final class PageRequestSupport {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 500;

    private PageRequestSupport() { }

    public static int normalizePage(int page) {
        return Math.max(0, page);
    }

    public static int normalizeSize(int size) {
        return size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    /**
     * An omitted page and size mean an unpaged-style listing bounded by MAX_SIZE.
     * Once the client supplies either pagination parameter, an omitted size uses DEFAULT_SIZE.
     */
    public static int resolvePageNumber(Integer page) {
        return normalizePage(page == null ? 0 : page);
    }

    public static int resolvePageSize(Integer page, Integer size) {
        if (size == null) {
            return page == null ? MAX_SIZE : DEFAULT_SIZE;
        }
        return normalizeSize(size);
    }

    public static Pageable create(int page, int size, Sort sort) {
        int safePage = normalizePage(page);
        int safeSize = normalizeSize(size);

        Sort stableSort = sort == null || sort.isUnsorted()
                ? Sort.by(Sort.Direction.ASC, "_id")
                : sort.getOrderFor("_id") == null
                    ? sort.and(Sort.by(Sort.Direction.ASC, "_id"))
                    : sort;
        return PageRequest.of(safePage, safeSize, stableSort);
    }
}
