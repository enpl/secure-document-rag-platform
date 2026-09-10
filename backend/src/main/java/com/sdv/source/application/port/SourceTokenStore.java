package com.sdv.source.application.port;

/**
 * F-BE-024. 안전한 OAuth Token 영속화 경계(SRC-003, SRC-010).
 *
 * 원본 Token 값은 절대 로그로 남기지 않는다. M04는 실제 구현체(암호화 저장
 * 등)를 만들지 않는다 - 이후 작업(M06 Google Drive 등)이 구현한다.
 */
public interface SourceTokenStore {

    void save(Long sourceId, String token);

    String load(Long sourceId);

    void delete(Long sourceId);
}
