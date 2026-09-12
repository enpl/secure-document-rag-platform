package com.sdv.rag.infrastructure.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * M06 신규 - {@link DocumentParsingClient}가 쓰는 {@link RestClient} Bean 조립을
 * Client 자체와 분리한다(Timeout 등 HTTP Transport 설정과, "Byte를 보내고
 * 응답을 해석한다"는 행위를 나눈다) - 테스트에서
 * {@code MockRestServiceServer.bindTo(RestClient.Builder)}로 만든 Stub
 * {@link RestClient}를 {@link DocumentParsingClient}에 그대로 주입할 수 있게
 * 한다.
 *
 * <p>{@code sdv.ai-service.url}은 이미 {@code application-local.yml}/
 * {@code application-compose.yml}에 존재하는 고정된 운영자 설정 내부
 * 주소다 - 요청이 URL을 고르지 않는다(새 설정을 추가하지 않았다).</p>
 */
@Configuration
public class AiServiceRestClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    // 서버 측 Parser 실행 상한(60s)보다 여유 있게 크다 - Client가 서버보다
    // 먼저 포기하면 서버가 실제로 작업을 끝냈는지 알 수 없는 채로 실패 처리하게 된다.
    private static final int READ_TIMEOUT_MS = 65_000;

    /**
     * 기본값은 {@code application-local.yml}의 기존 {@code AI_SERVICE_URL} 기본값과
     * 동일한 자리표시자(Placeholder)다 - 실제 운영/개발 배포는 그 설정(환경변수)이
     * 이 기본값을 덮어쓴다. 이 기본값 자체는 입력에서 오지 않는 고정 상수이므로
     * "요청이 URL을 고르지 않는다"는 요구사항과 무관하다 - 이 속성을 별도로
     * 지정하지 않는 기존 {@code @SpringBootTest}(M02~M05)가 Full Context를
     * 띄울 때 이 신규 필수 Bean 때문에 깨지지 않도록 하기 위한 것뿐이다
     * ({@code PermissionFreshnessPolicy}가 이미 M05에서 확립한 것과 동일한 패턴).
     */
    @Bean
    public RestClient aiServiceRestClient(RestClient.Builder builder,
            @Value("${sdv.ai-service.url:http://localhost:8000}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
