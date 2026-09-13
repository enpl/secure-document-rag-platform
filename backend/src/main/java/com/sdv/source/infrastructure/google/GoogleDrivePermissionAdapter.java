package com.sdv.source.infrastructure.google;

import com.sdv.source.domain.SourcePermission;
import com.sdv.source.domain.SourcePermissionsResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * F-BE-045 (M08 신규, Review 교정 반영). Google Permission 표현을 SDV
 * {@link SourcePermission} 목록으로 변환한다 - Principal 정규화 자체는
 * {@link PrincipalResolver}에 맡긴다(단일 책임 분리, CORE_SPEC §10이 둘을
 * 별도 파일로 정의).
 *
 * <p>Google Drive Role({@code owner/organizer/fileOrganizer/writer/
 * commenter/reader})은 전부 최소한 읽기를 포함한다 - SDV는 Read-only이므로
 * 이들을 전부 {@code "READ"} 하나로 매핑한다(SDV가 알려진 유일한 권한
 * 문자열, {@code SourcePermissionEntity}/{@code EffectivePermissionService}
 * 가 이미 이 값만 쓴다 - 새 권한 값을 지어내지 않는다). 인식되지 않는
 * Role은 조용히 "READ 아님"으로 넘기지 않고, 그 Permission 항목 전체를
 * 제외한다(Fail Closed - 존재를 알 수 없는 권한을 있다고 가정하지 않는다).</p>
 *
 * <h2>M08 Review 교정(항목 7) - {@code expirationTime} 반영</h2>
 * <p>원안은 Google Permission의 {@code expirationTime}을 요청도, 확인도
 * 하지 않았다 - 이미 만료된 권한도 여전히 유효한 권한처럼 Catalog에
 * 반영될 수 있었다(INV-SRC-002 위반 소지). 이제 {@code expirationTime}이
 * 있고 이미 지났으면 그 Permission 항목을 결과에서 제외한다(빈 목록으로
 * 수렴 - Fail Closed). 형식이 잘못돼 해석할 수 없는 값도 "만료되지 않은
 * 것으로 가정"하지 않고 동일하게 제외한다. 시각 판정은 결정론적 Test를
 * 위해 주입 가능한 {@link Clock}을 쓴다({@code PermissionFreshnessPolicy}가
 * 이미 확립한 것과 같은 관례).</p>
 */
@Component
public class GoogleDrivePermissionAdapter {

    private static final String READ = "READ";
    private static final Set<String> ROLES_IMPLYING_READ = Set.of("owner", "organizer", "fileOrganizer", "writer",
            "commenter", "reader");
    /**
     * M08 후속 교정 - {@link #listAllPermissions}의 안전한 순회 상한. 한 페이지가
     * 최대 100건(Client의 {@code pageSize=100})이므로 이 값으로도 실제 파일 하나가
     * 가질 수 있는 어떤 현실적인 권한 수보다 훨씬 넉넉하다(최대 백만 건) - 이 상한은
     * 정상적인 대용량 Permission 목록을 자르기 위한 것이 아니라, 반복/순환되는
     * {@code nextPageToken}을 Google이 절대 보내지 않는다는 가정이 깨졌을 때의
     * 마지막 안전망이다(아래 {@code seenPageTokens} 기반 순환 탐지가 1차 방어).
     */
    private static final int MAX_PERMISSION_PAGES = 10_000;

    private final PrincipalResolver principalResolver;
    private final GoogleDriveClient client;
    private final Clock clock;

    @Autowired
    public GoogleDrivePermissionAdapter(PrincipalResolver principalResolver, GoogleDriveClient client) {
        this(principalResolver, client, Clock.systemUTC());
    }

    /** 테스트가 통제된 {@link Clock}을 직접 주입하기 위한 패키지 전용 생성자(만료 판정 결정론화). */
    GoogleDrivePermissionAdapter(PrincipalResolver principalResolver, GoogleDriveClient client, Clock clock) {
        this.principalResolver = principalResolver;
        this.client = client;
        this.clock = clock;
    }

    /**
     * 모든 페이지를 끝까지 순회해 합친다 - 첫 페이지에서 자르지 않는다(이
     * 작업 지시사항: "Preserve pagination. Do not silently truncate...
     * permissions... at the first page.").
     *
     * <h2>M08 후속 교정 - 반복/순환 Page Token에 대한 유한 순회 보장</h2>
     * <p>원안은 {@code pageToken != null}인 동안 무한히 반복했다 - Google이
     * (오류나 손상된 응답으로) 이미 본 {@code nextPageToken}을 다시 돌려주면
     * 영원히 끝나지 않는다. 이제 지금까지 본 모든 Token을 {@code
     * seenPageTokens}에 기록해, 반복되는 단일 Token이든 여러 Token이 순환하는
     * Cycle이든 즉시 감지한다. {@link #MAX_PERMISSION_PAGES}는 그 탐지가
     * 어떤 이유로든 무력화될 경우를 대비한 마지막 안전망이다. 둘 중 하나라도
     * 걸리면 지금까지 모은 목록을 "완전한 결과"로 절대 반환하지 않는다 -
     * 일부만 모은 목록을 {@code OK}로 돌려주면 호출자가 권한이 실제보다
     * 적다고(또는 많다고) 잘못 믿을 수 있다(Fail Closed, {@code
     * SourcePermissionsResult.failed()}).</p>
     */
    public SourcePermissionsResult listAllPermissions(String accessToken, String fileId) {
        List<SourcePermission> collected = new ArrayList<>();
        Set<String> seenPageTokens = new HashSet<>();
        String pageToken = null;
        int pagesFetched = 0;
        try {
            do {
                if (pagesFetched >= MAX_PERMISSION_PAGES) {
                    return SourcePermissionsResult.failed();
                }
                GoogleDriveClient.GooglePermissionsPage page = client.listPermissions(accessToken, fileId, pageToken);
                pagesFetched++;
                if (page.permissions() != null) {
                    for (GoogleDriveClient.GooglePermission permission : page.permissions()) {
                        toSourcePermission(permission).ifPresent(collected::add);
                    }
                }
                pageToken = page.nextPageToken();
                if (pageToken != null && !seenPageTokens.add(pageToken)) {
                    // 이미 본 Token이 다시 왔다 - 반복(같은 Token) 또는 순환(여러 Token을
                    // 거쳐 되돌아옴) 모두 여기서 잡힌다. 계속 돌면 끝나지 않으므로 즉시 중단한다.
                    return SourcePermissionsResult.failed();
                }
            } while (pageToken != null);
        } catch (GoogleApiException e) {
            return switch (e.getCategory()) {
                case UNAUTHORIZED, PERMISSION_DENIED -> SourcePermissionsResult.unknown();
                default -> SourcePermissionsResult.failed();
            };
        }
        return SourcePermissionsResult.ok(collected);
    }

    private Optional<SourcePermission> toSourcePermission(GoogleDriveClient.GooglePermission permission) {
        if (permission.role() == null || !ROLES_IMPLYING_READ.contains(permission.role())) {
            return Optional.empty();
        }
        if (isExpired(permission.expirationTime())) {
            return Optional.empty();
        }
        return principalResolver.resolve(permission).map(principal -> new SourcePermission(principal, READ));
    }

    private boolean isExpired(String expirationTime) {
        if (expirationTime == null || expirationTime.isBlank()) {
            return false; // 만료 시각이 없으면 무기한 권한이다 - Google 자체의 기본 동작과 일치한다.
        }
        try {
            return !Instant.parse(expirationTime).isAfter(clock.instant());
        } catch (DateTimeParseException e) {
            // 해석할 수 없는 만료 시각 - 유효 기간을 신뢰할 수 없다(Fail Closed, 있다고 가정하지 않는다).
            return true;
        }
    }
}
