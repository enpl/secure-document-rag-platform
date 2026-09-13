package com.sdv.source.infrastructure.google;

import com.sdv.source.application.port.SourceTokenStore;
import com.sdv.source.application.port.TokenEnvelope;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * F-BE-044 (M08 MVP OAuth, {@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}). {@link
 * SourceTokenStore}의 실제 Production 구현체 - 이제까지 항상 비어있던 {@code
 * Optional<SourceTokenStore>}가 이 Bean이 등록됨으로써 처음으로 채워진다.
 *
 * <p>이 Class 자체는 얇다(Adapter) - 실제 암호화/영속화/Google 폐기 로직은 전부
 * {@link GoogleTokenService}(F-BE-043)에 있다. {@code SourceTokenStore} Port의
 * 메서드 이름({@code save}/{@code load}/{@code delete})과 {@link GoogleTokenService}의
 * 메서드 이름({@code store}/{@code load}/{@code revoke})이 다른 것은 의도적이다 -
 * File Manifest가 두 Class를 이미 서로 다른 이름/책임(Port 구현 대 실제 암호화
 * Service)으로 정의한다.</p>
 */
@Component
public class GoogleTokenStoreAdapter implements SourceTokenStore {

    private final GoogleTokenService googleTokenService;

    public GoogleTokenStoreAdapter(GoogleTokenService googleTokenService) {
        this.googleTokenService = googleTokenService;
    }

    @Override
    public void save(Long sourceId, TokenEnvelope token) {
        googleTokenService.store(sourceId, token);
    }

    @Override
    public Optional<TokenEnvelope> load(Long sourceId) {
        return googleTokenService.load(sourceId);
    }

    @Override
    public void delete(Long sourceId) {
        googleTokenService.revoke(sourceId);
    }
}
