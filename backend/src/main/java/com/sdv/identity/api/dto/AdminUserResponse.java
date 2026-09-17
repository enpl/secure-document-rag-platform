package com.sdv.identity.api.dto;

public record AdminUserResponse(Long id, String loginId, String displayName, String maximumClassification,
        boolean active, long authorizationRevision, long version) { }
