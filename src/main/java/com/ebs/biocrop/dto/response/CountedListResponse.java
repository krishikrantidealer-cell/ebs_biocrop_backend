package com.ebs.biocrop.dto.response;

import java.util.List;

/** A non-paginated list accompanied by the number of returned items. */
public record CountedListResponse<T>(long count, List<T> items) {
    public CountedListResponse {
        items = items == null ? List.of() : List.copyOf(items);
        count = items.size();
    }

    public static <T> CountedListResponse<T> from(List<T> items) {
        return new CountedListResponse<>(items == null ? 0 : items.size(), items);
    }
}
