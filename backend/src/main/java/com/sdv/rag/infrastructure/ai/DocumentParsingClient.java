package com.sdv.rag.infrastructure.ai;

import com.sdv.rag.domain.ExtractedLocation;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.domain.ParseOutcome;
import com.sdv.rag.domain.ParseOutcomeKind;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * F-BE-108(경로/이름은 Manifest 확정, M06이 구현). Python AI Service(F-AI-001/002/003)의
 * {@code /parse}만 호출한다({@code /index}는 M11+ 범위, 여기서 구현하지 않는다).
 *
 * <p>고정된 운영자 설정 내부 주소만 사용한다({@code sdv.ai-service.url} - 이미
 * {@code application-local.yml}/{@code application-compose.yml}에 존재하는 설정,
 * 새로 추가하지 않았다, {@link AiServiceRestClientConfig} 참고) - 요청이 URL을
 * 고르지 않는다. Parser는 Byte/Metadata만 받는다 - 로컬 경로나 임의 URL을 절대
 * 넘기지 않는다.</p>
 *
 * <p>Java는 Parsing을 직접 하지 않는다(병렬 구현 금지) - 이 Client는 순수 HTTP
 * 왕복과 응답을 {@link ParseOutcome}으로 매핑하는 얇은 계층이다. 형식 검증/MIME
 * 대조/파싱/정규화는 전부 Python 쪽 책임이다.</p>
 */
@Component
public class DocumentParsingClient {

    private final RestClient restClient;

    public DocumentParsingClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 이미 Fetch된 원본 Byte를 Python {@code /parse}로 전송한다. {@code declaredMimeType}은
     * Source가 보고한 값일 뿐 증거가 아니다(Python이 실제 내용과 대조한다).
     * 네트워크/Timeout/5xx 등 호출 자체가 실패하면 {@link ParseOutcomeKind#FAILED}로
     * 변환해 반환한다(예외를 호출자에게 그대로 노출하지 않는다) - 원본 예외 메시지는
     * 로그/감사에 남기지 않는다.
     */
    public ParseOutcome parse(byte[] content, String fileName, String declaredMimeType) {
        try {
            ParseResponse response = restClient.post()
                    .uri("/parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipartBody(content, fileName, declaredMimeType))
                    .retrieve()
                    .body(ParseResponse.class);
            return toOutcome(response);
        } catch (RestClientException e) {
            return ParseOutcome.failure(ParseOutcomeKind.FAILED, "AI service call failed");
        }
    }

    /**
     * {@code MultipartBodyBuilder}(Reactive Stack, {@code reactor-core} 필요)
     * 대신 {@link RestClient}(Servlet Stack)와 함께 쓰는 표준 비Reactive
     * 방식인 {@link MultiValueMap}을 직접 구성한다 - 등록된
     * {@code FormHttpMessageConverter}가 이 형태를 {@code multipart/form-data}로
     * 직렬화한다.
     */
    private static MultiValueMap<String, Object> multipartBody(byte[] content, String fileName,
            String declaredMimeType) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        parts.add("declaredMimeType", declaredMimeType == null ? "" : declaredMimeType);
        return parts;
    }

    private static ParseOutcome toOutcome(ParseResponse response) {
        if (response == null || response.outcome() == null) {
            return ParseOutcome.failure(ParseOutcomeKind.FAILED, "empty parse response");
        }
        ParseOutcomeKind kind = parseKind(response.outcome());
        if (kind == ParseOutcomeKind.SUCCESS) {
            List<ExtractedLocation> locations = response.locations() == null
                    ? List.of()
                    : response.locations().stream().map(ParsedLocation::toDomain).toList();
            return ParseOutcome.success(response.parserName(), response.parserVersion(),
                    response.normalizationVersion(), response.normalizedText(), locations);
        }
        String reason = response.reason() == null ? "unspecified" : response.reason();
        return ParseOutcome.failure(kind, reason);
    }

    private static ParseOutcomeKind parseKind(String rawOutcome) {
        try {
            return ParseOutcomeKind.valueOf(rawOutcome);
        } catch (IllegalArgumentException e) {
            // 인식 불가 값 - Fail Closed로 실패 취급한다.
            return ParseOutcomeKind.FAILED;
        }
    }

    /** {@code /parse} 응답 JSON 매핑 전용 - 패키지 밖으로 노출하지 않는다. */
    record ParseResponse(String outcome, String parserName, String parserVersion, String normalizationVersion,
            String normalizedText, List<ParsedLocation> locations, String reason) {
    }

    record ParsedLocation(String locatorType, String locatorValue, int startOffset, int endOffset) {
        ExtractedLocation toDomain() {
            return new ExtractedLocation(LocatorType.valueOf(locatorType), locatorValue, startOffset, endOffset);
        }
    }
}
