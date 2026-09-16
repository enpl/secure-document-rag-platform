package com.sdv.ai.application;

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

        List<String> topic = new ArrayList<>();
        for (String raw : tokens) {
            if (MIME_BY_TOKEN.containsKey(raw) || COMMAND_WORDS.contains(raw)) continue;
            String token = stripParticle(raw);
            if (!token.isBlank() && !COMMAND_WORDS.contains(token)) topic.add(token);
        }
        String q = topic.isEmpty() ? null : String.join(" ", topic);
        if (q != null && q.length() > MAX_QUERY_CHARS) return new Result(null, "FILE_QUERY_TOO_LONG");
        int boundedSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        return new Result(new RagFileSearchQuery(q, mime, null, from, to, RagFileSortKey.MODIFIED_AT_DESC, 0,
                boundedSize), null);
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
