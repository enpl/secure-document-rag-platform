package com.sdv.admin.api;

import com.sdv.admin.application.DependencyHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * F-BE-014. Admin 의존성 Health API(INS-003, OPS-006).
 *
 * <p>{@code GET /api/admin/health} - v3.2 Core Spec §32 Core REST API Boundaries에
 * 이미 정의된 경로를 그대로 사용한다. 자격증명/환경값/내부 예외 세부사항을 절대
 * 반환하지 않는다 - 의존성 이름별 상태 문자열만 반환한다.</p>
 */
@RestController
@RequestMapping("/api/admin/health")
public class AdminHealthController {

    private final DependencyHealthService dependencyHealthService;

    public AdminHealthController(DependencyHealthService dependencyHealthService) {
        this.dependencyHealthService = dependencyHealthService;
    }

    @GetMapping
    public Map<String, String> getHealth() {
        return dependencyHealthService.checkAll();
    }
}
