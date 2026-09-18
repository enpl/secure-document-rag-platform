package com.sdv.ai.application;

import com.sdv.rag.api.dto.RagFileNameMatch;
import com.sdv.rag.api.dto.RagFileSearchQuery;
import com.sdv.rag.api.dto.RagFileSortKey;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small validated Korean metadata-discovery grammar; unsupported constraints never broaden. */
@Component
public class NaturalLanguageFileQueryParser {
    public record Result(RagFileSearchQuery query, String failureReason) { }

    private static final int MAX_QUERY_CHARS = 200;
    private static final int MAX_PAGE_SIZE = 50;
    private static final Map<String, String> MIME_BY_TOKEN = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("zip", "application/zip"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"));
    private static final Set<String> COMMAND_WORDS = Set.of(
            "파일", "문서", "찾아줘", "찾아", "찾기", "검색해줘", "검색", "보여줘", "보여",
            "어제", "오늘", "지난주", "수정된", "수정한", "변경된", "변경한");

    /**
     * M17 자연어 파일 검색 교정 - "sdv로 시작하는"/"sdv가 들어간" 같은 구조화된
     * 접두어/포함 문구를 인식한다. 인용구(따옴표)는 매칭 전에 제거한다({@link
     * #QUOTE_CHARS}) - 파일명 자체에 유효한 문자(마침표/밑줄/붙임표)를 잘라내지
     * 않도록 Token 문자 집합은 {@link #tokenize}와 동일하게 유지한다. 두 마커가
     * 동시에 매칭되면(서로 충돌하는 조건) 명확화를 요청한다 - 조용히 하나를
     * 고르거나 CONTAINS로 대체하지 않는다.
     */
    private static final Pattern QUOTE_CHARS = Pattern.compile("[\"'“”‘’「」『』]");
    private static final Pattern PREFIX_MARKER = Pattern.compile(
            "([\\p{L}\\p{N}._-]+?)\\s*(?:으로|로)\\s*(?:시작하는|시작되는|시작하|시작되|시작)");
    private static final Pattern CONTAINS_MARKER = Pattern.compile(
            "([\\p{L}\\p{N}._-]+?)(?:가|이)?\\s*(?:들어간|들어있는|들어\\s*있는|포함된|포함하는|포함한|포함되어\\s*있는)");

    private final Clock clock;
    private final ZoneId zone;

    public NaturalLanguageFileQueryParser() {
        this(Clock.systemUTC(), ZoneId.of("Asia/Seoul"));
    }

    NaturalLanguageFileQueryParser(Clock clock, ZoneId zone) {
        this.clock = clock;
        this.zone = zone;
    }

    public Result parse(String question, int pageSize) {
        String lower = question.toLowerCase(Locale.ROOT);
        if (lower.matches(".*(큰 파일|대용량|소유자|작성자|공유자|owner|shared by).*")) {
            return new Result(null, "UNSUPPORTED_FILE_CONSTRAINT");
        }
        if (lower.matches(".*(작성한|작성된|생성한|생성된|만든).*(어제|오늘|지난주).*"
                + "|.*(어제|오늘|지난주).*(작성한|작성된|생성한|생성된|만든).*")) {
            return new Result(null, "CREATED_DATE_UNSUPPORTED");
        }

        List<String> tokens = tokenize(lower);
        String mime = null;
        for (String token : tokens) {
            String candidate = MIME_BY_TOKEN.get(token);
            if (candidate == null) continue;
            if (mime != null && !mime.equals(candidate)) return new Result(null, "AMBIGUOUS_FILE_TYPE");
            mime = candidate;
        }

        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant from = null;
        Instant to = null;
        if (tokens.contains("오늘")) {
            from = today.atStartOfDay(zone).toInstant();
            to = today.plusDays(1).atStartOfDay(zone).toInstant();
        } else if (tokens.contains("어제")) {
            from = today.minusDays(1).atStartOfDay(zone).toInstant();
            to = today.atStartOfDay(zone).toInstant();
        } else if (tokens.contains("지난주")) {
            LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            from = thisMonday.minusWeeks(1).atStartOfDay(zone).toInstant();
            to = thisMonday.atStartOfDay(zone).toInstant();
        }

        NameMatchExtraction nameMatchExtraction = extractNameMatch(lower);
        if (nameMatchExtraction != null && nameMatchExtraction.conflict()) {
            return new Result(null, "AMBIGUOUS_FILE_NAME_CONDITION");
        }

        String q;
        RagFileNameMatch nameMatch;
        if (nameMatchExtraction != null) {
            // 구조화된 접두어/포함 문구가 명시적으로 있었다 - 남은 문구를 추가로
            // 검색어에 합치지 않는다("sdv"만, "sdv 시작하는"처럼 조사/동사가
            // 섞여 들어가지 않는다).
            q = nameMatchExtraction.token();
            nameMatch = nameMatchExtraction.mode();
        } else {
            List<String> topic = new ArrayList<>();
            for (String raw : tokens) {
                if (MIME_BY_TOKEN.containsKey(raw) || COMMAND_WORDS.contains(raw)) continue;
                String token = stripParticle(raw);
                if (!token.isBlank() && !COMMAND_WORDS.contains(token)) topic.add(token);
            }
            q = topic.isEmpty() ? null : String.join(" ", topic);
            nameMatch = RagFileNameMatch.CONTAINS;
        }
        if (q != null && q.length() > MAX_QUERY_CHARS) return new Result(null, "FILE_QUERY_TOO_LONG");
        int boundedSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        return new Result(new RagFileSearchQuery(q, nameMatch, mime, null, from, to, RagFileSortKey.MODIFIED_AT_DESC,
                0, boundedSize), null);
    }

    /**
     * 두 마커 중 하나만 매칭되면 그 값을, 둘 다 매칭되면(서로 충돌하는 조건)
     * {@link NameMatchExtraction#conflict()}를, 둘 다 없으면(구조화된 문구
     * 없음) {@code null}을 반환한다 - 기존 일반 검색어 결합 경로로 넘긴다.
     */
    private static NameMatchExtraction extractNameMatch(String lower) {
        String dequoted = QUOTE_CHARS.matcher(lower).replaceAll(" ");
        Matcher prefixMatcher = PREFIX_MARKER.matcher(dequoted);
        boolean prefixFound = prefixMatcher.find();
        Matcher containsMatcher = CONTAINS_MARKER.matcher(dequoted);
        boolean containsFound = containsMatcher.find();
        if (prefixFound && containsFound) {
            return NameMatchExtraction.conflicting();
        }
        if (prefixFound) {
            String token = prefixMatcher.group(1).trim();
            return token.isEmpty() ? null : new NameMatchExtraction(RagFileNameMatch.PREFIX, token, false);
        }
        if (containsFound) {
            String token = containsMatcher.group(1).trim();
            return token.isEmpty() ? null : new NameMatchExtraction(RagFileNameMatch.CONTAINS, token, false);
        }
        return null;
    }

    private record NameMatchExtraction(RagFileNameMatch mode, String token, boolean conflict) {
        static NameMatchExtraction conflicting() {
            return new NameMatchExtraction(null, null, true);
        }
    }

    private static List<String> tokenize(String text) {
        String normalized = text.replaceAll("[^\\p{L}\\p{N}._-]+", " ").trim();
        return normalized.isEmpty() ? List.of() : List.of(normalized.split("\\s+"));
    }

    private static String stripParticle(String value) {
        if (value.length() > 1) {
            for (String suffix : List.of("으로", "에서", "에게", "까지", "부터", "을", "를", "은", "는", "이", "가")) {
                if (value.endsWith(suffix) && value.length() > suffix.length()) {
                    return value.substring(0, value.length() - suffix.length());
                }
            }
        }
        return value;
    }
}
