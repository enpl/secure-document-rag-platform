package com.sdv.source.infrastructure.google;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * M08 MVP OAuth ({@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}) - {@link GoogleOAuthClient}가
 * 쓰는 {@link RestClient} Bean 조립을 Client 자체와 분리한다 - {@link
 * GoogleDriveRestClientConfig}(M08)가 이미 확립한 것과 동일한 패턴(Timeout 등 Transport
 * 설정과 "Byte를 보내고 응답을 해석한다"는 행위를 나눠, Test에서 Base URL만 바꾼
 * {@link RestClient}를 주입할 수 있게 한다).
 *
 * <p>이 Bean은 Base URL을 고정하지 않는다({@link GoogleDriveRestClientConfig}와의 차이) -
 * Google OAuth Token Endpoint/Revoke Endpoint는 서로 다른 절대 URL이고, 실제 호출
 * 대상은 {@link GoogleOAuthClient}가 매번 절대 URL(설정 가능한 {@code
 * sdv.google-oauth.token-uri}/{@code sdv.google-oauth.revoke-uri})로 직접 지정한다 -
 * Spring Security의 {@code RestClientAuthorizationCodeTokenResponseClient}/{@code
 * RestClientRefreshTokenTokenResponseClient}도 자신에게 전달된 {@code ClientRegistration}의
 * Token URI를 절대 URL로 그대로 쓴다(이 Bean의 {@code baseUrl}을 참조하지 않는다).</p>
 */
@Configuration
public class GoogleOAuthRestClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /** OAuth Token/Revoke 호출은 Google Drive Content 전송과 달리 한 번의 짧은 JSON 응답만 받는다 - 별도 절대 Deadline이 필요 없다. */
    private static final int READ_TIMEOUT_MS = 10_000;

    @Bean
    @Qualifier("googleOAuthRestClient")
    public RestClient googleOAuthRestClient(RestClient.Builder builder) {
        return builder.requestFactory(boundedRequestFactory()).build();
    }

    /**
     * {@link GoogleOAuthClient}가 Authorization-Code/Refresh-Token 교환에만 쓰는 별도
     * {@link RestClient} - Spring Security의 {@code RestClientAuthorizationCodeTokenResponseClient}/
     * {@code RestClientRefreshTokenTokenResponseClient}에 {@code setRestClient(...)}로
     * 주입한다. 이 둘은 주입된 RestClient를 완전히 대체품으로 쓴다(자신의 기본
     * Converter를 追加로 유지하지 않는다) - 그래서 Google Token Endpoint 응답
     * ({@code access_token}/{@code token_type}/{@code expires_in}/{@code scope} 등
     * 표준 OAuth2 JSON)을 실제로 해석할 수 있는 {@link OAuth2AccessTokenResponseHttpMessageConverter}와,
     * 요청 본문을 {@code application/x-www-form-urlencoded}로 인코딩하는 {@link
     * FormHttpMessageConverter}를 이 RestClient에 명시적으로 등록해야 한다 - 그러지
     * 않으면 일반 Jackson Converter가 Snake_case 필드를 못 알아듣고 빈 응답 객체를
     * 만들어(예외 없이) 이후 {@code accessToken}이 null인 채로 넘어간다.
     */
    @Bean
    @Qualifier("googleOAuthTokenResponseRestClient")
    public RestClient googleOAuthTokenResponseRestClient(RestClient.Builder builder) {
        return builder.requestFactory(boundedRequestFactory())
                .configureMessageConverters(converters -> converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .build();
    }

    private static SimpleClientHttpRequestFactory boundedRequestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        return requestFactory;
    }
}
