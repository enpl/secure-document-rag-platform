package com.sdv.security.application;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/** Canonical entry point used after one document's provider ACL observation is committed. */
@Service
public class PermissionSyncRiskService {
    private final OversharingDetectionService detector;
    @Autowired
    public PermissionSyncRiskService(OversharingDetectionService detector) { this.detector = detector; }
    private PermissionSyncRiskService() { this.detector = OversharingDetectionService.noop(); }
    public static PermissionSyncRiskService noop() { return new PermissionSyncRiskService(); }
    public void inspect(Long documentId) { detector.inspect(documentId); }
}
