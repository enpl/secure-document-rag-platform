package com.sdv.rag.infrastructure.ai;

import com.sdv.rag.domain.ParseOutcome;
import com.sdv.rag.domain.ParseOutcomeKind;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link DocumentParsingClient}의 순수 계약(Contract) 테스트 - 실제 AI Service를
 * 띄우지 않고 {@link MockRestServiceServer}로 HTTP Stub을 세운다(Postgres/실제
 * 네트워크 불필요, {@code AiServiceRestClientConfig} 없이 이 테스트에서 직접
 * {@link RestClient}를 구성한다).
 */
class DocumentParsingClientTest {

    private MockRestServiceServer server;
    private DocumentParsingClient client;

    private void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai-service.internal");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DocumentParsingClient(builder.build());
    }

    @Test
    void successResponseMapsToSuccessOutcome() {
        setUp();
        server.expect(requestTo("http://ai-service.internal/parse"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "outcome": "SUCCESS",
                          "parserName": "charset-normalizer",
                          "parserVersion": "decode-only",
                          "normalizationVersion": "1",
                          "normalizedText": "hello world",
                          "locations": [{"locatorType": "DOCUMENT", "locatorValue": "1", "startOffset": 0, "endOffset": 11}]
                        }
                        """, MediaType.APPLICATION_JSON));

        ParseOutcome outcome = client.parse("hello world".getBytes(), "notes.txt", "text/plain");

        assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.SUCCESS);
        assertThat(outcome.normalizedText()).isEqualTo("hello world");
        assertThat(outcome.locations()).hasSize(1);
        assertThat(outcome.locations().get(0).locatorValue()).isEqualTo("1");
        server.verify();
    }

    @Test
    void unsupportedFormatResponseMapsToFailureOutcome() {
        setUp();
        server.expect(requestTo("http://ai-service.internal/parse"))
                .andRespond(withSuccess("""
                        {"outcome": "UNSUPPORTED_FORMAT", "reason": "unsupported or mismatched format"}
                        """, MediaType.APPLICATION_JSON));

        ParseOutcome outcome = client.parse(new byte[] {1, 2, 3}, "legacy.doc", "application/msword");

        assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.UNSUPPORTED_FORMAT);
        assertThat(outcome.reason()).isEqualTo("unsupported or mismatched format");
    }

    @Test
    void unrecognizedOutcomeValueFailsClosed() {
        setUp();
        server.expect(requestTo("http://ai-service.internal/parse"))
                .andRespond(withSuccess("""
                        {"outcome": "SOMETHING_NEW", "reason": "future value"}
                        """, MediaType.APPLICATION_JSON));

        ParseOutcome outcome = client.parse("x".getBytes(), "a.txt", "text/plain");

        assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.FAILED);
    }

    @Test
    void serverErrorResponseIsTranslatedToFailureWithoutExposingRawException() {
        setUp();
        server.expect(requestTo("http://ai-service.internal/parse"))
                .andRespond(withServerError());

        ParseOutcome outcome = client.parse("x".getBytes(), "a.txt", "text/plain");

        assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.FAILED);
        assertThat(outcome.reason()).isEqualTo("AI service call failed");
    }

    @Test
    void connectionFailureIsTranslatedToFailureOutcome() {
        setUp();
        server.expect(requestTo("http://ai-service.internal/parse"))
                .andRespond(request -> {
                    throw new IOException("simulated connection refused");
                });

        ParseOutcome outcome = client.parse("x".getBytes(), "a.txt", "text/plain");

        assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.FAILED);
        assertThat(outcome.reason()).isEqualTo("AI service call failed");
    }
}
