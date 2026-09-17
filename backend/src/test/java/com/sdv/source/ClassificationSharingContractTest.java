package com.sdv.source;

import com.sdv.policy.domain.SecurityLevel;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.domain.DocumentShare;
import com.sdv.source.domain.ShareAction;
import com.sdv.source.domain.ShareAudience;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/** Red-first guard for the clearance/audience contract introduced by V013. */
class ClassificationSharingContractTest {

    @Test
    void securityLevelsExposeAnExplicitNonOrdinalRank() throws Exception {
        Method rank = SecurityLevel.class.getDeclaredMethod("rank");

        assertThat(rank.invoke(SecurityLevel.PUBLIC)).isEqualTo(0);
        assertThat(rank.invoke(SecurityLevel.INTERNAL)).isEqualTo(1);
        assertThat(rank.invoke(SecurityLevel.CONFIDENTIAL)).isEqualTo(2);
        assertThat(rank.invoke(SecurityLevel.SECRET)).isEqualTo(3);
    }

    @Test
    void persistedShareCarriesAnExplicitAudience() throws Exception {
        Method audience = DocumentShareEntity.class.getDeclaredMethod("getAudience");

        assertThat(audience.getReturnType()).isEqualTo(String.class);
    }

    @Test
    void allAuthenticatedAudienceAllowsZeroRecipientsButNamedAudienceDoesNot() {
        DocumentShare all = new DocumentShare(1L, "owner", 2L, 3L, ShareAudience.ALL_AUTHENTICATED,
                SecurityLevel.INTERNAL, Set.of(ShareAction.VIEW), Set.of(), false, null, 1L,
                Instant.now(), Instant.now(), null);
        assertThat(all.grants("reader", ShareAction.VIEW)).isTrue();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DocumentShare(1L, "owner", 2L, 3L,
                ShareAudience.NAMED_USERS, SecurityLevel.INTERNAL, Set.of(ShareAction.VIEW), Set.of(), false,
                null, 1L, Instant.now(), Instant.now(), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
