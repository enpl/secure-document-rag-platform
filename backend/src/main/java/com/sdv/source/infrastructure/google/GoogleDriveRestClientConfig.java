package com.sdv.source.infrastructure.google;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * M08 신규 - {@link GoogleDriveClient}가 쓰는 {@link RestClient} Bean 조립을
 * Client 자체와 분리한다 - {@code com.sdv.rag.infrastructure.ai.AiServiceRestClientConfig}
 * (M06)가 이미 확립한 것과 동일한 패턴이다(Timeout 등 Transport 설정과
 * "Byte를 보내고 응답을 해석한다"는 행위를 나눠, Test에서 Base URL만 바꾼
 * {@link RestClient}를 주입할 수 있게 한다 - 이 작업의
 * {@code GoogleDriveConnectorContractTest}가 실제 Google 대신 Local Mock
 * HTTP Server를 가리키도록 이 Bean의 {@code baseUrl}만 재정의한다).
 *
 * <p>{@code @Qualifier("googleDriveRestClient")}로 이름을 구분한다 - 이미
 * 존재하는 {@code aiServiceRestClient} Bean과 같은 {@link RestClient} Type을
 * Spring이 혼동하지 않게 한다.</p>
 */
@Configuration
public class GoogleDriveRestClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /**
     * 개별 Socket Read 호출 하나에 대한 상한이다(M08 Review 교정 - 이전 주석은
     * "Content 전송은 별도의 더 긴 상한을 쓴다"고 잘못 설명했다: Content
     * 전송도 결국 이 같은 RestClient/Socket을 그대로 쓰므로 별도로 더 긴
     * 값이 존재하지 않는다). 이 값만으로는 "느리게 조금씩 오는(Slow
     * Trickle)" 응답을 막지 못한다 - 각 개별 Read는 짧게짧게 계속 성공할 수
     * 있기 때문이다. 그래서 {@link GoogleDriveClient}가 재시도+Backoff+
     * Streaming 전체를 아우르는 별도의 절대 작업 Deadline({@code
     * sdv.google-drive.operation-deadline-ms})을 추가로 둔다.
     */
    private static final int READ_TIMEOUT_MS = 15_000;

    /**
     * 기본값 {@code https://www.googleapis.com}은 실제 Google Drive REST API
     * Base URL이다 - 요청이 URL을 고르지 않는다(고정 상수). Test는 이 속성을
     * Local Mock HTTP Server 주소로 덮어쓴다.
     */
    @Bean
    @Qualifier("googleDriveRestClient")
    public RestClient googleDriveRestClient(RestClient.Builder builder,
            @Value("${sdv.google-drive.api-base-url:https://www.googleapis.com}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
