package com.sdv.source.domain;

/**
 * M10B 신규 - {@link DocumentShare}가 수신자에게 부여할 수 있는 행위(CORE_SPEC
 * §2A.13 "action grants"). 이번 M10B Slice가 실제로 검증/강제하는 값은
 * {@link #VIEW}(File Metadata Discovery)뿐이다 - {@link #DOWNLOAD}는 저장은
 * 되지만(공유 계약의 일부로 이미 정의해 두기 위함), 이 Slice의 어떤 Endpoint도
 * 이를 근거로 Content Byte를 내보내지 않는다(SDV 인가 다운로드는 M10C 범위).
 */
public enum ShareAction {
    VIEW,
    DOWNLOAD
}
