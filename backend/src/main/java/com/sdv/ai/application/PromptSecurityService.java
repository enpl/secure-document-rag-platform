package com.sdv.ai.application;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic first line for clear policy/provider/system-instruction bypass attempts. */
@Service
public class PromptSecurityService {
    private static final Pattern BYPASS = Pattern.compile(
            "((권한|정책).{0,16}(우회|무시)|숨겨진.{0,12}(지침|프롬프트).{0,12}(보여|공개)|"
            + "(우회|무시).{0,16}(권한|정책|지침)|provider.{0,12}(switch|change)|"
            + "(ignore|bypass|override).{0,30}(permission|policy|instruction|system)|"
            + "(show|reveal).{0,20}(system prompt|hidden instruction))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public boolean isClearBypass(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        return BYPASS.matcher(normalized).find();
    }
}
