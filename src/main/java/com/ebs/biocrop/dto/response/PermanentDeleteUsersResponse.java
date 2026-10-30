package com.ebs.biocrop.dto.response;

import java.util.List;

public record PermanentDeleteUsersResponse(
        long count,
        List<String> deletedUserIds
) {
    public PermanentDeleteUsersResponse {
        deletedUserIds = deletedUserIds == null ? List.of() : List.copyOf(deletedUserIds);
        count = deletedUserIds.size();
    }

    public static PermanentDeleteUsersResponse of(List<String> deletedUserIds) {
        return new PermanentDeleteUsersResponse(
                deletedUserIds == null ? 0 : deletedUserIds.size(),
                deletedUserIds
        );
    }
}
