package com.sdv.rag.application;

import com.sdv.common.model.UserContext;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.rag.api.dto.RagCitation;
import com.sdv.rag.domain.EvidenceProvenance;
import com.sdv.source.domain.ShareAction;
import com.sdv.source.domain.SourceAccessContext;
import org.springframework.stereotype.Service;

/** Citation metadata is server-owned; download links require an independent DOWNLOAD decision. */
@Service
public class CitationAssembler {
    private final EffectivePermissionService permissions;

    public CitationAssembler(EffectivePermissionService permissions) { this.permissions = permissions; }

    public RagCitation assemble(UserContext requester, EvidenceProvenance p) {
        SourceAccessContext download = new SourceAccessContext(requester.subject(), p.publisherSubject(), p.sourceId(),
                p.documentId(), p.shareId(), ShareAction.DOWNLOAD, p.shareGeneration(), p.connectionGeneration(),
                p.requesterAuthorizationRevision());
        String downloadUrl = permissions.evaluateSharedAccess(requester, download, null).isAllowed()
                ? "/api/shares/" + p.shareId() + "/download" : null;
        return new RagCitation(p.documentId(), p.locatorType().name(), p.locatorValue(), p.sourceVersion(),
                p.verifiedAt(), downloadUrl);
    }
}
