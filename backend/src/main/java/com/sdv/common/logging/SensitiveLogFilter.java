package com.sdv.common.logging;

import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * F-BE-013. 재사용 가능한 민감정보 마스킹 유틸리티(AUD-004, OPS-002).
 *
 * <p><b>실제 강제 경계(Enforcement Boundary):</b> 이 클래스는 이 클래스의
 * {@link #mask(String)}/{@link #isSensitiveKey(String)}를 실제로 호출하는 코드에
 * 대해서만 마스킹을 보장한다 - M02에서는 {@code AuditService}의 metadata
 * sanitization과 이 로그 문장들이 이를 사용한다. 이 유틸리티는 향후 작성될 모든
 * 로깅 코드를 자동으로 안전하게 만들어주지 않는다 - 이 메서드를 호출하지 않는 로그
 * 문장은 여전히 민감정보를 그대로 남길 수 있다. 로깅 프레임워크 자체를 교체하거나
 * 모든 로그 출력을 가로채지 않는다.</p>
 *
 * <p><b>남은 위험:</b> 알려진 key 이름과 Bearer Token 형태를 기반으로 한 정규식
 * 매칭이며, key 없이 등장하는 완전 자유 형식 원문(예: 문서 전체가 key 표시 없이
 * 로그에 그대로 이어붙여진 경우)까지 의미적으로 감지하지는 못한다.</p>
 *
 * <p><b>값 경계가 모호할 때의 정책:</b> 따옴표로 감싸이지 않은(unquoted) 민감 값은
 * 어디서 끝나는지 정규식만으로 안전하게 판단할 수 없다(공백/줄바꿈이 값의 일부일
 * 수도, 다음 필드의 시작일 수도 있음). 이런 모호한 경우 값의 일부만 마스킹해 뒷부분을
 * 그대로 흘리는 대신, key 뒤에 남은 나머지 전체를 마스킹한다(과다 마스킹을
 * 선호) - 이는 의미 기반 감지가 아니라 보수적인 안전장치다.</p>
 */
public final class SensitiveLogFilter {

    public static final String MASK = "***MASKED***";

    private static final String KEY_NAMES =
            "password|pwd|token|access[_-]?token|refresh[_-]?token|"
                    + "api[_-]?key|encryption[_-]?key|secret|content|document|question|prompt";

    private static final Pattern SENSITIVE_KEY_NAME = Pattern.compile("(?i)^(" + KEY_NAMES + ")$");

    /** {@code Bearer <token>} 형태(예: Authorization Header 값) - 토큰은 공백을 포함하지 않는다. */
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9\\-._~+/]+=*");

    /**
     * 따옴표로 감싸인 값(JSON-like {@code "key":"value"}, 또는 {@code key="value"} /
     * {@code key='value'}). Escape된 따옴표({@code \"})를 값의 일부로 올바르게
     * 인식하며, 실제(unescaped) 종료 따옴표에서 정확히 멈춘다 - 경계가 명확하므로
     * 값만 마스킹하고 그 뒤는 건드리지 않는다.
     */
    private static final Pattern QUOTED_VALUE = Pattern.compile(
            "(?is)\\b(" + KEY_NAMES + ")\\b(\")?(\\s*[:=]\\s*)"
                    + "(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')");

    /**
     * 따옴표 없는(unquoted) 값. 경계가 모호하므로 key 뒤에 남은 나머지 전체(개행
     * 포함)를 마스킹한다 - 일부 단어/줄만 마스킹하고 뒤를 흘리지 않는다.
     */
    private static final Pattern UNQUOTED_VALUE_REMAINDER = Pattern.compile(
            "(?is)\\b(" + KEY_NAMES + ")\\b(\\s*[:=]\\s*)(?![\"'])(\\S[\\s\\S]*)");

    private SensitiveLogFilter() {
    }

    /**
     * 주어진 key 이름이 알려진 민감 카테고리(Token/Password/Key/Secret/
     * Content/Document/Question/Prompt)에 해당하는지 대소문자 구분 없이,
     * 지원되는 표기 변형(access_token/accessToken/access-token 등)을 포함해
     * 판단한다. 호출자는 key가 민감하다고 판단되면 값의 내용과 무관하게 값
     * 전체를 교체해야 한다({@link #MASK} 사용).
     */
    public static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEY_NAME.matcher(key).matches();
    }

    /**
     * 알려진 민감 카테고리(Bearer/Access/Refresh Token, Password, API/암호화 Key,
     * Secret, 원문 Content/Document/Question/Prompt)를 key=value, header-like,
     * JSON-like(Escape된 따옴표 포함), Bearer Token 형태에서 찾아 값을 마스킹한다.
     * 따옴표 없는 값은 경계가 모호하므로 나머지 전체를 마스킹한다(위 클래스 문서
     * 참고). 원본 값은 절대 그대로 반환하지 않는다.
     */
    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String masked = input;
        masked = BEARER_TOKEN.matcher(masked).replaceAll("Bearer " + MASK);
        masked = QUOTED_VALUE.matcher(masked).replaceAll(SensitiveLogFilter::replaceQuotedValue);
        masked = UNQUOTED_VALUE_REMAINDER.matcher(masked)
                .replaceAll(mr -> mr.group(1) + mr.group(2) + MASK);
        return masked;
    }

    private static String replaceQuotedValue(MatchResult match) {
        String key = match.group(1);
        String keyQuote = match.group(2) == null ? "" : match.group(2);
        String separator = match.group(3);
        String quotedValue = match.group(4);
        char quoteChar = quotedValue.charAt(0);
        return key + keyQuote + separator + quoteChar + MASK + quoteChar;
    }
}
