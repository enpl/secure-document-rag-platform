package com.sdv.auth.api.dto;

import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;

import java.util.Set;

/**
 * F-BE-017. {@code GET /api/me}의 안전한 응답 - {@link UserContext}의 공식
 * 필드(subject/email/roles/groups)만 그대로 노출한다.
 */
public record MeResponse(String subject, String email, Set<Role> roles, Set<String> groups) {

    public static MeResponse from(UserContext userContext) {
        return new MeResponse(
                userContext.subject(),
                userContext.email(),
                userContext.roles(),
                userContext.groups());
    }
}
