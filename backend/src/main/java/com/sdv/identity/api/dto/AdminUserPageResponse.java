package com.sdv.identity.api.dto;

import java.util.List;

public record AdminUserPageResponse(List<AdminUserResponse> items, boolean hasMore) { }
