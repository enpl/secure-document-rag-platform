package com.sdv.identity.application;

import com.sdv.audit.application.AuditService;
import com.sdv.identity.api.dto.AdminUserPageResponse;
import com.sdv.identity.api.dto.AdminUserResponse;
import com.sdv.identity.api.dto.DirectoryUserResponse;
import com.sdv.identity.domain.UserAuthorizationChangedEvent;
import com.sdv.identity.domain.UserAuthorizationSnapshot;
import com.sdv.identity.infrastructure.persistence.entity.SdvUserEntity;
import com.sdv.identity.infrastructure.persistence.repository.SdvUserJpaRepository;
import com.sdv.policy.domain.SecurityLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class IdentityRegistryService {
    public static final int DIRECTORY_LIMIT = 8;
    private static final int ADMIN_MAX_PAGE_SIZE = 50;
    private final SdvUserJpaRepository repository;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final String configuredIssuer;

    @Autowired
    public IdentityRegistryService(SdvUserJpaRepository repository, AuditService auditService,
            ApplicationEventPublisher events, @Value("${sdv.keycloak.issuer-uri}") String configuredIssuer) {
        this.repository = repository;
        this.auditService = auditService;
        this.events = events;
        this.configuredIssuer = requireIssuer(configuredIssuer);
    }

    /** Materializes only validated successful logins and never assigns/reset clearance or active state. */
    @Transactional
    public void observeValidatedLogin(String issuer, String subject, String loginId, String displayName) {
        if (!configuredIssuer.equals(requireIssuer(issuer)) || subject == null || subject.isBlank()) return;
        String safeLogin;
        try {
            safeLogin = validateLoginId(loginId);
        } catch (IdentityAccessException invalidLabel) {
            return;
        }
        String normalized = normalizeLoginId(safeLogin);
        String safeDisplay = safeDisplayName(displayName);
        repository.observeValidatedIdentity(configuredIssuer, subject, safeLogin, normalized, safeDisplay);
    }

    @Transactional(readOnly = true)
    public Optional<UserAuthorizationSnapshot> currentAuthorization(String issuer, String subject) {
        if (!configuredIssuer.equals(issuer) || subject == null || subject.isBlank()) return Optional.empty();
        return repository.findByIssuerAndSubject(issuer, subject).filter(SdvUserEntity::isActive)
                .flatMap(IdentityRegistryService::toSnapshot);
    }

    @Transactional(readOnly = true)
    public RegistryStatus registryStatus(String issuer, String subject) {
        if (!configuredIssuer.equals(issuer) || subject == null || subject.isBlank()) {
            return RegistryStatus.REGISTRY_UNAVAILABLE;
        }
        return repository.findByIssuerAndSubject(issuer, subject).map(user -> {
            if (!user.isActive()) return RegistryStatus.DISABLED;
            if (user.getMaxClassification() == null) return RegistryStatus.CLEARANCE_UNSET;
            try {
                SecurityLevel.valueOf(user.getMaxClassification());
                return RegistryStatus.READY;
            } catch (IllegalArgumentException malformed) {
                return RegistryStatus.REGISTRY_UNAVAILABLE;
            }
        }).orElse(RegistryStatus.REGISTRY_UNAVAILABLE);
    }

    @Transactional(readOnly = true)
    public List<DirectoryUserResponse> searchDirectory(String issuer, String query) {
        requireConfiguredIssuer(issuer);
        String normalized = normalizeQuery(query, 2, 50);
        return repository.searchDirectory(issuer, containsPattern(normalized), PageRequest.of(0, DIRECTORY_LIMIT))
                .stream().map(IdentityRegistryService::toDirectory).toList();
    }

    @Transactional(readOnly = true)
    public List<ResolvedRecipient> resolveRecipients(String issuer, Set<Long> selectedIds) {
        requireConfiguredIssuer(issuer);
        if (selectedIds == null || selectedIds.isEmpty() || selectedIds.size() > 20
                || selectedIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IdentityAccessException(IdentityAccessException.Reason.INVALID_REQUEST,
                    "named audience requires one to twenty selected users");
        }
        Set<Long> distinct = new LinkedHashSet<>(selectedIds);
        List<SdvUserEntity> rows = repository.findAllByIdInAndIssuer(distinct, issuer);
        if (rows.size() != distinct.size()) {
            throw new IdentityAccessException(IdentityAccessException.Reason.NOT_FOUND, "selected user is unavailable");
        }
        Map<Long, ResolvedRecipient> resolved = new LinkedHashMap<>();
        for (SdvUserEntity row : rows) {
            if (!row.isActive() || repository.countByIssuerAndNormalizedLoginIdAndActiveTrue(
                    issuer, row.getNormalizedLoginId()) != 1) {
                throw new IdentityAccessException(IdentityAccessException.Reason.AMBIGUOUS,
                        "selected user is unavailable or ambiguous");
            }
            resolved.put(row.getId(), new ResolvedRecipient(row.getId(), row.getSubject(), row.getLoginId(),
                    row.getDisplayName()));
        }
        return distinct.stream().map(resolved::get).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, DirectoryUserResponse> labelsBySubjects(String issuer, Set<String> subjects) {
        Map<String, DirectoryUserResponse> labels = new LinkedHashMap<>();
        if (!configuredIssuer.equals(issuer) || subjects == null) return labels;
        for (String subject : subjects) {
            repository.findByIssuerAndSubject(issuer, subject)
                    .ifPresent(user -> labels.put(subject, toDirectory(user)));
        }
        return Map.copyOf(labels);
    }

    @Transactional(readOnly = true)
    public AdminUserPageResponse adminSearch(String query, int page, int size) {
        if (page < 0 || size < 1 || size > ADMIN_MAX_PAGE_SIZE) invalid("invalid page or size");
        String pattern = query == null || query.isBlank() ? "" : containsPattern(normalizeQuery(query, 1, 50));
        Page<SdvUserEntity> result = repository.searchAdmin(configuredIssuer, pattern, PageRequest.of(page, size));
        return new AdminUserPageResponse(result.getContent().stream().map(IdentityRegistryService::toAdmin).toList(),
                result.hasNext());
    }

    @Transactional(readOnly = true)
    public AdminUserResponse adminGet(Long id) {
        return toAdmin(requireUser(id));
    }

    @Transactional
    public AdminUserResponse updateAccess(String actorSubject, Long id, long expectedVersion,
            String maximumClassification, boolean active) {
        SdvUserEntity user = requireUser(id);
        if (user.getVersion() != expectedVersion) {
            throw new IdentityAccessException(IdentityAccessException.Reason.CONFLICT, "user access changed");
        }
        String parsed = parseNullableClassification(maximumClassification);
        String old = user.getMaxClassification();
        boolean oldActive = user.isActive();
        if (!user.changeAccess(parsed, active)) return toAdmin(user);
        try {
            repository.saveAndFlush(user);
        } catch (ObjectOptimisticLockingFailureException failure) {
            throw new IdentityAccessException(IdentityAccessException.Reason.CONFLICT, "user access changed");
        }
        auditService.record(actorSubject, "USER_ACCESS_CHANGED", "sdv-user:" + user.getId(), "SUCCESS", "OK",
                Map.of("oldClassification", old == null ? "UNSET" : old,
                        "newClassification", parsed == null ? "UNSET" : parsed,
                        "oldActive", Boolean.toString(oldActive), "newActive", Boolean.toString(active)));
        events.publishEvent(new UserAuthorizationChangedEvent(user.getIssuer(), user.getSubject(),
                user.getAuthorizationRevision()));
        return toAdmin(user);
    }

    private SdvUserEntity requireUser(Long id) {
        if (id == null) invalid("user id is required");
        return repository.findById(id).filter(u -> configuredIssuer.equals(u.getIssuer()))
                .orElseThrow(() -> new IdentityAccessException(IdentityAccessException.Reason.NOT_FOUND,
                        "user not found"));
    }

    private void requireConfiguredIssuer(String issuer) {
        if (!configuredIssuer.equals(issuer)) invalid("identity issuer mismatch");
    }

    private static Optional<UserAuthorizationSnapshot> toSnapshot(SdvUserEntity user) {
        try {
            if (user.getMaxClassification() == null) return Optional.empty();
            return Optional.of(new UserAuthorizationSnapshot(user.getId(), user.getIssuer(), user.getSubject(),
                    user.getLoginId(), SecurityLevel.valueOf(user.getMaxClassification()),
                    user.getAuthorizationRevision()));
        } catch (IllegalArgumentException invalidStoredValue) {
            return Optional.empty();
        }
    }

    private static DirectoryUserResponse toDirectory(SdvUserEntity user) {
        return new DirectoryUserResponse(user.getId(), user.getLoginId(), user.getDisplayName());
    }

    private static AdminUserResponse toAdmin(SdvUserEntity user) {
        return new AdminUserResponse(user.getId(), user.getLoginId(), user.getDisplayName(),
                user.getMaxClassification(), user.isActive(), user.getAuthorizationRevision(), user.getVersion());
    }

    private static String parseNullableClassification(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return SecurityLevel.valueOf(raw.trim()).name(); }
        catch (IllegalArgumentException failure) { invalid("invalid classification"); return null; }
    }

    private static String validateLoginId(String raw) {
        if (raw == null) invalid("login id is required");
        String value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC);
        if (value.isBlank() || value.length() > 100 || value.chars().anyMatch(Character::isISOControl)) {
            invalid("invalid login id");
        }
        return value;
    }

    private static String normalizeLoginId(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String normalizeQuery(String raw, int min, int max) {
        if (raw == null) invalid("query is required");
        String value = normalizeLoginId(raw.trim());
        if (value.length() < min || value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            invalid("invalid query length");
        }
        return value;
    }

    private static String containsPattern(String value) {
        return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String safeDisplayName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC);
        return value.length() <= 200 && value.chars().noneMatch(Character::isISOControl) ? value : null;
    }

    private static String requireIssuer(String issuer) {
        if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("issuer is required");
        return issuer;
    }

    private static void invalid(String message) {
        throw new IdentityAccessException(IdentityAccessException.Reason.INVALID_REQUEST, message);
    }

    public record ResolvedRecipient(Long id, String subject, String loginId, String displayName) { }

    public enum RegistryStatus { READY, CLEARANCE_UNSET, DISABLED, REGISTRY_UNAVAILABLE }
}
