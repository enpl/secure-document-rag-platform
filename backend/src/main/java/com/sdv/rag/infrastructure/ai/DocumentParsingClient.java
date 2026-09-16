package com.sdv.rag.infrastructure.ai;

import com.sdv.rag.domain.EmbeddingChunk;
import com.sdv.rag.domain.ExtractedLocation;
import com.sdv.rag.domain.IndexOutcome;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.domain.ParseOutcome;
import com.sdv.rag.domain.ParseOutcomeKind;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * F-BE-108(경로/이름은 Manifest 확정, M06이 구현, M11이 {@code /index}를 추가).
 * Python AI Service(F-AI-001/002/003/M11 신규 F-AI-index)의 {@code /parse}와
 * {@code /index}를 호출한다.
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

    /**
     * M08 후속 교정 - {@code @Qualifier}: {@code GoogleDriveRestClientConfig}가
     * 두 번째 {@link RestClient} Bean({@code googleDriveRestClient})을 추가하면서,
     * 이 생성자의 원래 Qualifier 없는 주입이 더 이상 결정적이지 않게 됐다(Bean이
     * 2개면 Spring이 고를 수 없다) - 동작은 그대로 두고 어떤 Bean을 원하는지만
     * 명시한다.
     */
    public DocumentParsingClient(@Qualifier("aiServiceRestClient") RestClient restClient) {
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

    /**
     * M11 신규 - 이미 Fetch된 원본 Byte를 Python {@code /index}로 전송해 Parse+Chunk+
     * Embed 전체 Pipeline을 한 번의 호출로 수행한다. 응답에는 평문 Chunk Text가 전혀
     * 담기지 않는다 - Embedding Vector/일반화된 Locator/Content HMAC/Version 메타데이터
     * 뿐이다({@link IndexOutcome} Class Javadoc 참고). {@code /parse}와 동일하게
     * 네트워크/Timeout/5xx 등 호출 자체의 실패는 {@link ParseOutcomeKind#FAILED}로
     * 변환한다 - 원본 예외 메시지는 절대 옮기지 않는다.
     */
    public IndexOutcome index(byte[] content, String fileName, String declaredMimeType) {
        try {
            IndexResponse response = restClient.post()
                    .uri("/index")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipartBody(content, fileName, declaredMimeType))
                    .retrieve()
                    .body(IndexResponse.class);
            return toIndexOutcome(response);
        } catch (RestClientException e) {
            return IndexOutcome.failure(ParseOutcomeKind.FAILED, "AI service call failed");
        }
    }

    private static IndexOutcome toIndexOutcome(IndexResponse response) {
        if (response == null || response.outcome() == null) {
            return IndexOutcome.failure(ParseOutcomeKind.FAILED, "empty index response");
        }
        ParseOutcomeKind kind = parseKind(response.outcome());
        if (kind != ParseOutcomeKind.SUCCESS) {
            String reason = response.reason() == null ? "unspecified" : response.reason();
            return IndexOutcome.failure(kind, reason);
        }
        if (response.chunks() == null || response.chunks().isEmpty() || response.parserVersion() == null
                || response.chunkingVersion() == null || response.embeddingModel() == null) {
            return IndexOutcome.failure(ParseOutcomeKind.FAILED, "malformed successful index response");
        }
        List<EmbeddingChunk> chunks;
        try {
            chunks = response.chunks().stream().map(IndexChunkResponse::toDomain).toList();
        } catch (RuntimeException malformed) {
            // 예: 인식 불가 locatorType, 빈 embedding - 원본 예외 메시지를 옮기지 않는다.
            return IndexOutcome.failure(ParseOutcomeKind.FAILED, "malformed chunk in index response");
        }
        return IndexOutcome.success(response.parserVersion(), response.chunkingVersion(), response.embeddingModel(),
                chunks);
    }

    /** {@code /index} 응답 JSON 매핑 전용 - 패키지 밖으로 노출하지 않는다. */
    record IndexResponse(String outcome, String parserVersion, String chunkingVersion, String embeddingModel,
            Integer embeddingDimensions, List<IndexChunkResponse> chunks, String reason) {
    }

    record IndexChunkResponse(int chunkIndex, String locatorType, String locatorValue, List<Double> embedding,
            String contentHmac) {
        EmbeddingChunk toDomain() {
            List<Float> floats = embedding == null ? List.of()
                    : embedding.stream().map(Double::floatValue).toList();
            return new EmbeddingChunk(chunkIndex, LocatorType.valueOf(locatorType), locatorValue, floats,
                    contentHmac);
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
