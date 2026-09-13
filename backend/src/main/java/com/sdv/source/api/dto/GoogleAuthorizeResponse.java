package com.sdv.source.api.dto;

/**
 * M08 MVP OAuth ({@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}) - {@code GET
 * /api/admin/sources/google/authorize} 응답. Frontend가 이 URL로 Browser를 직접
 * Navigate시킨다(Keycloak Bearer Credential은 이 URL 자체에 담기지 않는다 - Query
 * String에 인증정보를 넣지 않는다).
 */
public record GoogleAuthorizeResponse(String authorizationUrl) {
}
