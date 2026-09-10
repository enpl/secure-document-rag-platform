package com.sdv.auth.api;

import com.sdv.auth.api.dto.MeResponse;
import com.sdv.common.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F-BE-016. {@code GET /api/me} - 현재 인증된 사용자 정보(AUT-002).
 *
 * <p>Request Body/Query Parameter/Header에서 신원 정보를 읽지 않으며, Repository도
 * 조회하지 않는다 - 오직 이미 검증된 {@link CurrentUserProvider}(검증된 JWT
 * Context)에서만 신원을 가져온다. 그래서 호출자가 {@code subject}/{@code userId}/
 * {@code roles} 같은 이름의 Query Parameter나 Header를 아무리 보내도 응답 신원에
 * 영향을 줄 수 없다.</p>
 */
@RestController
public class MeController {

    private final CurrentUserProvider currentUserProvider;

    public MeController(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/api/me")
    public MeResponse getMe() {
        return MeResponse.from(currentUserProvider.getCurrentUser());
    }
}
