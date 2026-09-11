package com.sdv.policy.domain;

/**
 * M05 구현 결정(SPEC GAP 아님 - v3.2 Manifest가 AI 요청 자체를 표현하는 별도
 * 파일을 정의하지 않는다): {@link com.sdv.policy.application.EffectivePermissionService}가
 * "AI 사용이 요청되었는가"와 "어떤 Provider(Local/External)가 요청되었는가"를
 * 표현하기 위한 최소 크기의 Application-level 입력 값 객체.
 *
 * <p>호출자가 이 값을 아예 넘기지 않으면(참조가 {@code null}) "AI 사용이 요청되지
 * 않음"을 뜻하며, {@code EffectivePermissionService}는 AI Usage Policy 단계를
 * 완전히 건너뛴다(v3.2 §17 "AI Usage Policy (when AI is requested)"). 이 객체가
 * 존재하면 AI 사용이 요청된 것이며, {@link #externalProviderRequested()}만으로
 * Local/External 여부를 구분한다 - 특정 Provider 이름/구현을 이 계층에서
 * 알 필요가 없다(LLM Provider 호출은 M05 범위 밖).</p>
 */
public record AiRequestContext(boolean externalProviderRequested) {

    public static AiRequestContext local() {
        return new AiRequestContext(false);
    }

    public static AiRequestContext external() {
        return new AiRequestContext(true);
    }
}
