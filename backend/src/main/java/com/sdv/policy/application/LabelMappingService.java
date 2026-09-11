package com.sdv.policy.application;

import com.sdv.policy.domain.SecurityLevel;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * F-BE-084. Source 라벨 → {@link SecurityLevel} 매핑(POL-006).
 *
 * <p><b>M05 구현 결정(SPEC GAP 아님, 설계 결정):</b> v3.2 명세는 실제 Source별
 * 라벨(예: Google Drive Sensitivity Label 이름, SharePoint 분류 태그)을
 * {@link SecurityLevel}로 변환하는 구체적인 매핑 테이블/규칙을 정의하지
 * 않는다 - 아직 어떤 Source 동기화(Sync, M10)도 이 Service를 호출하지 않는다.
 * 가짜 매핑 데이터를 발명하지 않기 위해, 이 M05 구현은 가장 보수적이고
 * 검증 가능한 규칙만 제공한다: Source가 제공한 원문 라벨 문자열이 SDV
 * {@link SecurityLevel}의 네 canonical 이름 중 하나와 대소문자 무관하게
 * 정확히 일치할 때만 매핑한다. 그 외의 모든 라벨(별칭, Source 고유 이름 등)은
 * 매핑할 수 없다고 보고 {@link Optional#empty()}를 반환한다 - 호출자는 이를
 * "자동 매핑 불가, MANUAL 라벨링 필요"로 처리해야 한다(Fail Closed, 임의
 * 추측 금지). 향후 실제 Source별 별칭 매핑 테이블이 필요해지면 별도 설계
 * 결정으로 확장한다.</p>
 */
@Service
public class LabelMappingService {

    public Optional<SecurityLevel> resolve(String sourceLabel) {
        if (sourceLabel == null || sourceLabel.isBlank()) {
            return Optional.empty();
        }
        String normalized = sourceLabel.trim().toUpperCase(Locale.ROOT);
        for (SecurityLevel level : SecurityLevel.values()) {
            if (level.name().equals(normalized)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
