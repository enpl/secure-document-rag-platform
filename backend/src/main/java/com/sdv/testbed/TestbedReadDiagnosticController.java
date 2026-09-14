package com.sdv.testbed;

import com.sdv.common.security.CurrentUserProvider;
import com.sdv.testbed.dto.TestbedReadDiagnosticRequest;
import com.sdv.testbed.dto.TestbedReadDiagnosticResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M16A follow-up local testbed diagnostic
 * ({@code docs/runbooks/M16A_LOCAL_TESTBED.md}) - not a canonical File/
 * Feature ID.
 *
 * <h2>Registration is double-gated</h2>
 * <p>This bean registers only when BOTH conditions hold: the {@code
 * testbed} Spring profile is active AND {@code
 * sdv.testbed.diagnostics.enabled=true}. Neither alone is enough - simply
 * running the testbed profile does not expose this endpoint, and the flag
 * has no effect on any other profile even if set there by mistake.</p>
 *
 * <h2>Why no SecurityConfig change was needed</h2>
 * <p>The path is under {@code /api/admin/**}, which {@code SecurityConfig}
 * already restricts to {@code hasRole("ADMIN")} for every profile - this
 * class relies entirely on that existing rule plus the existing JWT
 * filter/{@link CurrentUserProvider}. It does not add {@code permitAll}, does
 * not weaken JWT validation, and does not trust any frontend-supplied role
 * claim - only the validated Keycloak token's role authorities.</p>
 */
@RestController
@RequestMapping("/api/admin/testbed/diagnostics")
@Profile("testbed")
@ConditionalOnProperty(prefix = "sdv.testbed.diagnostics", name = "enabled", havingValue = "true")
public class TestbedReadDiagnosticController {

    private final TestbedReadDiagnosticService diagnosticService;
    private final CurrentUserProvider currentUserProvider;

    public TestbedReadDiagnosticController(TestbedReadDiagnosticService diagnosticService,
            CurrentUserProvider currentUserProvider) {
        this.diagnosticService = diagnosticService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Accepts only {@code sourceId} + {@code fileId} - never a raw URL,
     * credential, or caller-supplied subject/owner. The authenticated
     * administrator's identity comes only from {@link CurrentUserProvider}
     * (the validated JWT), exactly like every other {@code /api/admin/**}
     * endpoint.
     */
    @PostMapping("/read-check")
    public TestbedReadDiagnosticResponse readCheck(@Valid @RequestBody TestbedReadDiagnosticRequest request) {
        var admin = currentUserProvider.getCurrentUser();
        return diagnosticService.checkRead(admin, request.sourceId(), request.fileId());
    }

    /**
     * M16A follow-up (safety correction) - a trivial, logic-free signal the
     * frontend calls once before offering the diagnostic form. Its only
     * purpose is to let the frontend distinguish "this endpoint genuinely
     * does not exist here" (any failure calling THIS endpoint - it has no
     * business logic that can fail at runtime) from "it exists and a real
     * error occurred" (a failure calling {@link #readCheck} after this one
     * already succeeded). Without this, both cases surface as the same
     * generic 500 from {@code GlobalExceptionHandler}'s catch-all and were
     * indistinguishable from the frontend alone.
     */
    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("enabled", true);
    }
}
