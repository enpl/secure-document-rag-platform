package com.sdv.sync.application.job;

import com.sdv.source.domain.SourceChangePage;
import com.sdv.source.domain.SourceChangeRecord;
import com.sdv.source.domain.SourceChangeType;
import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourceDocumentState;
import com.sdv.source.domain.DocumentIndexStatus;
import com.sdv.source.domain.SourcePermissionsResult;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.sync.application.SourceSyncPageWriter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GoogleDriveSyncJobDeadlineTest {

    @Test
    void aPageFetchedBeforeTheDeadlineIsNotCommittedWhenTheExternalCallReturnsAtTheDeadline() {
        Long sourceId = 41L;
        Instant deadline = Instant.parse("2026-09-14T12:00:00Z");
        GoogleDriveConnector connector = mock(GoogleDriveConnector.class);
        SourceSyncPageWriter pageWriter = mock(SourceSyncPageWriter.class);
        when(connector.findChanges(sourceId, "cursor-before-deadline"))
                .thenReturn(new SourceChangePage(List.of(), null, "cursor-after-deadline", true));
        Clock clock = new StepClock(deadline.minusNanos(1), deadline);
        GoogleDriveSyncJob job = new GoogleDriveSyncJob(connector, pageWriter, clock);

        GoogleDriveSyncJob.SyncPageLoopResult result = job.runIncrementalSync(sourceId, 99L,
                "cursor-before-deadline", deadline, 10);

        assertThat(result.fullyComplete()).isFalse();
        verify(connector).findChanges(sourceId, "cursor-before-deadline");
        verifyNoInteractions(pageWriter);
    }

    @Test
    void permissionsFetchedBeforeTheDeadlineAreNotCommittedWhenThatExternalCallCrossesTheDeadline() {
        Long sourceId = 42L;
        Instant deadline = Instant.parse("2026-09-14T12:00:00Z");
        Instant before = deadline.minusNanos(1);
        SourceDocument document = new SourceDocument(null, sourceId, "external-file", "External.txt", "text/plain",
                "v1", before, SourceDocumentState.ACTIVE, DocumentIndexStatus.PENDING, null);
        GoogleDriveConnector connector = mock(GoogleDriveConnector.class);
        SourceSyncPageWriter pageWriter = mock(SourceSyncPageWriter.class);
        when(connector.findChanges(sourceId, "cursor-before-permissions"))
                .thenReturn(new SourceChangePage(List.of(new SourceChangeRecord("external-file",
                        SourceChangeType.CHANGED, document)), null, "cursor-after-permissions", true));
        when(connector.getPermissions(sourceId, "external-file")).thenReturn(SourcePermissionsResult.ok(List.of()));
        Clock clock = new StepClock(before, before, before, deadline);
        GoogleDriveSyncJob job = new GoogleDriveSyncJob(connector, pageWriter, clock);

        GoogleDriveSyncJob.SyncPageLoopResult result = job.runIncrementalSync(sourceId, 100L,
                "cursor-before-permissions", deadline, 10);

        assertThat(result.fullyComplete()).isFalse();
        verify(connector).getPermissions(sourceId, "external-file");
        verifyNoInteractions(pageWriter);
    }

    private static final class StepClock extends Clock {
        private final Instant[] instants;
        private final AtomicInteger reads = new AtomicInteger();

        private StepClock(Instant... instants) {
            this.instants = instants.clone();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            int index = Math.min(reads.getAndIncrement(), instants.length - 1);
            return instants[index];
        }
    }
}
