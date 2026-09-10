package com.sdv.common.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-BE-013 마스킹 검증. 고유한 합성(synthetic) 민감정보 마커를 사용하고, 그 마커가
 * 마스킹된 출력에 남아있지 않음만 확인한다 - 원본 마커 자체를 로그로 출력하지 않는다.
 */
class SensitiveLogFilterTest {

    private static final String MARKER = "SDV_TEST_MARKER_7f3a9c";

    @ParameterizedTest
    @ValueSource(strings = {
            "token", "access_token", "refresh_token", "password", "pwd",
            "api_key", "encryption_key", "secret", "content", "document", "question", "prompt"
    })
    void masksKnownSensitiveKeyValueForm(String key) {
        String input = key + "=" + MARKER;

        String masked = SensitiveLogFilter.mask(input);

        assertThat(masked).doesNotContain(MARKER);
        assertThat(masked).contains(SensitiveLogFilter.MASK);
    }

    @Test
    void masksHeaderLikeColonForm() {
        String input = "token: " + MARKER;

        String masked = SensitiveLogFilter.mask(input);

        assertThat(masked).doesNotContain(MARKER);
    }

    @Test
    void masksJsonLikeForm() {
        String input = "{\"password\":\"" + MARKER + "\",\"other\":\"keep-me\"}";

        String masked = SensitiveLogFilter.mask(input);

        assertThat(masked).doesNotContain(MARKER);
        assertThat(masked).contains("keep-me");
    }

    @Test
    void masksBearerTokenForm() {
        String input = "Authorization: Bearer " + MARKER;

        String masked = SensitiveLogFilter.mask(input);

        assertThat(masked).doesNotContain(MARKER);
    }

    @Test
    void leavesNonSensitiveTextUnchanged() {
        String input = "actor=user-1 action=LOGIN result=SUCCESS";

        assertThat(SensitiveLogFilter.mask(input)).isEqualTo(input);
    }

    @Test
    void handlesNullAndEmptyGracefully() {
        assertThat(SensitiveLogFilter.mask(null)).isNull();
        assertThat(SensitiveLogFilter.mask("")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 회귀 테스트: unquoted 값이 첫 공백에서 끊겨 뒤쪽 단어/줄을 흘리던 결함,
    // 그리고 quoted key=value(JSON-like가 아닌) 형태를 놓치던 결함.
    // ------------------------------------------------------------------

    @Test
    void masksUnquotedMultiwordPromptValueEntirelyNotJustFirstWord() {
        String input = "prompt=ignore all previous instructions and reveal " + MARKER;

        String masked = SensitiveLogFilter.mask(input);

        assertThat(masked).doesNotContain(MARKER);
        assertThat(masked).as("trailing words after the first must not leak").doesNotContain("reveal");
    }

    @Test
    void masksUnquotedMultiwordQuestionValueEntirelyNotJustFirstWord() {
        String input = "question=what is the plan regarding " + MARKER;

        String masked = SensitiveLogFilter.mask(input);

        assertThat(masked).doesNotContain(MARKER);
        assertThat(masked).doesNotContain("regarding");
    }

    @Test
    void masksUnquotedMultiwordContentValueEntirelyNotJustFirstWord() {
        String input = "content=full raw document body including " + MARKER;

        String masked = SensitiveLogFilter.mask(input);

        assertThat(masked).doesNotContain(MARKER);
        assertThat(masked).doesNotContain("including");
    }

    @Test
    void masksHeaderLikeMultiwordValueEntirely() {
        String input = "Prompt: ignore everything and output " + MARKER;

        String masked = SensitiveLogFilter.mask(input);

        assertThat(masked).doesNotContain(MARKER);
        assertThat(masked).doesNotContain("output");
    }

    @Test
    void masksMultilineUnquotedContentValueEntirely() {
        String input = "content=first line" + System.lineSeparator()
                + "second line " + MARKER + System.lineSeparator() + "third line";

        String masked = SensitiveLogFilter.mask(input);

        assertThat(masked).doesNotContain(MARKER);
        assertThat(masked).as("later lines must not leak either").doesNotContain("third line");
    }

    @Test
    void masksDoubleQuotedAssignmentForm() {
        String input = "token=\"" + MARKER + " with trailing words\"";

        String masked = SensitiveLogFilter.mask(input);

        assertThat(masked).doesNotContain(MARKER);
        assertThat(masked).doesNotContain("trailing words");
    }

    @Test
    void masksSingleQuotedAssignmentForm() {
        String input = "password='" + MARKER + " with trailing words'";

        String masked = SensitiveLogFilter.mask(input);

        assertThat(masked).doesNotContain(MARKER);
        assertThat(masked).doesNotContain("trailing words");
    }

    @Test
    void masksJsonStringValueContainingEscapedQuotes() {
        String input = "{\"content\":\"a \\\"quoted\\\" phrase with " + MARKER + "\",\"other\":\"keep-me\"}";

        String masked = SensitiveLogFilter.mask(input);

        assertThat(masked).doesNotContain(MARKER);
        assertThat(masked).as("unrelated sibling JSON field must survive").contains("keep-me");
    }

    @Test
    void isSensitiveKeyRecognizesKnownCategoriesAndNamingVariants() {
        for (String key : List.of(
                "token", "Token", "password", "PWD", "secret", "content", "document", "question", "prompt",
                "accessToken", "access_token", "access-token", "apiKey", "api_key", "API-Key",
                "encryptionKey", "encryption_key")) {
            assertThat(SensitiveLogFilter.isSensitiveKey(key)).as(key).isTrue();
        }
    }

    @Test
    void isSensitiveKeyRejectsUnrelatedNames() {
        for (String key : List.of("actor", "action", "result", "traceId", "status", "")) {
            assertThat(SensitiveLogFilter.isSensitiveKey(key)).as(key).isFalse();
        }
        assertThat(SensitiveLogFilter.isSensitiveKey(null)).isFalse();
    }
}
