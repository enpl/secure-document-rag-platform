package com.sdv.common.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F-BE-008: subject/email/roles/groups 4개 필드만 가지며, roles/groups는 불변이어야 한다. */
class UserContextTest {

    @Test
    void rolesAndGroupsAreImmutable() {
        UserContext context = new UserContext(
                "subject-1", "user@example.com", new HashSet<>(Set.of(Role.USER)), new HashSet<>(Set.of("group-a")));

        assertThatThrownBy(() -> context.roles().add(Role.ADMIN))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> context.groups().add("group-b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullRolesAndGroupsBecomeEmptyImmutableSets() {
        UserContext context = new UserContext("subject-1", null, null, null);

        assertThat(context.roles()).isEmpty();
        assertThat(context.groups()).isEmpty();
        assertThatThrownBy(() -> context.roles().add(Role.USER))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
