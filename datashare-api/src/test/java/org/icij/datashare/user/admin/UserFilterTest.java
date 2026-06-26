package org.icij.datashare.user.admin;

import org.icij.datashare.user.User;
import org.junit.Test;
import java.util.Map;
import static org.fest.assertions.Assertions.assertThat;

public class UserFilterTest {

    @Test
    public void test_empty_filter_matches_everything() {
        User u = new User("alice", "Alice", "alice@x.com");
        assertThat(new UserFilter(null).matches(u)).isTrue();
    }

    @Test
    public void test_q_matches_uid() {
        User u = new User("alice", "Alice", "alice@x.com");
        assertThat(new UserFilter("ali").matches(u)).isTrue();
    }

    @Test
    public void test_q_matches_name() {
        User u = new User("alice", "Alice Smith", "alice@x.com");
        assertThat(new UserFilter("smith").matches(u)).isTrue();
    }

    @Test
    public void test_q_matches_email() {
        User u = new User("alice", "Alice", "alice@corp.com");
        assertThat(new UserFilter("corp").matches(u)).isTrue();
    }

    @Test
    public void test_q_is_case_insensitive() {
        User u = new User("ALICE", "ALICE", "ALICE@X.COM");
        assertThat(new UserFilter("alice").matches(u)).isTrue();
    }

    @Test
    public void test_q_no_match() {
        User u = new User("alice", "Alice", "alice@x.com");
        assertThat(new UserFilter("zzz").matches(u)).isFalse();
    }

    @Test
    public void test_isEmpty_true_when_q_null() {
        assertThat(new UserFilter(null).isEmpty()).isTrue();
    }

    @Test
    public void test_isEmpty_false_when_q_set() {
        assertThat(new UserFilter("x").isEmpty()).isFalse();
    }
}
