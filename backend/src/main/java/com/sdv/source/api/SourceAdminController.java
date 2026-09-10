package com.sdv.source.api;

import com.sdv.common.security.CurrentUserProvider;
import com.sdv.source.api.dto.CreateSourceRequest;
import com.sdv.source.api.dto.SourceResponse;
import com.sdv.source.api.mapper.SourceApiMapper;
import com.sdv.source.application.SourceConnectionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F-BE-027. Source 관리 API(SRC-002, SRC-010) - {@code GET/POST
 * /api/admin/sources}, {@code DELETE /api/admin/sources/{id}}
 * (v3.2 Core Spec §32에 이미 정의된 경로).
 *
 * <p>HTTP 관심사만 다룬다 - JpaRepository를 직접 호출하지 않는다. 소유자는
 * 항상 {@link CurrentUserProvider}(검증된 JWT)에서만 가져온다 - Request
 * Body/Query Parameter/Header에서 절대 읽지 않는다.</p>
 */
@RestController
@RequestMapping("/api/admin/sources")
public class SourceAdminController {

    private final SourceConnectionService sourceConnectionService;
    private final SourceApiMapper sourceApiMapper;
    private final CurrentUserProvider currentUserProvider;

    public SourceAdminController(SourceConnectionService sourceConnectionService, SourceApiMapper sourceApiMapper,
            CurrentUserProvider currentUserProvider) {
        this.sourceConnectionService = sourceConnectionService;
        this.sourceApiMapper = sourceApiMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public List<SourceResponse> list() {
        String ownerSubject = currentUserProvider.getCurrentUser().subject();
        return sourceConnectionService.list(ownerSubject).stream()
                .map(sourceApiMapper::toResponse)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SourceResponse create(@Valid @RequestBody CreateSourceRequest request) {
        String ownerSubject = currentUserProvider.getCurrentUser().subject();
        var created = sourceConnectionService.create(sourceApiMapper.toDomain(request, ownerSubject));
        return sourceApiMapper.toResponse(created);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable Long id) {
        String ownerSubject = currentUserProvider.getCurrentUser().subject();
        sourceConnectionService.disconnect(id, ownerSubject);
    }
}
