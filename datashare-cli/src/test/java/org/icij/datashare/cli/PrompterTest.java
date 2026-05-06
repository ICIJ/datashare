package org.icij.datashare.cli;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;

public class PrompterTest {

    private Prompter prompter(String stdin, Supplier<char[]> passwordSrc, StringWriter sink) {
        return new Prompter(
                new BufferedReader(new StringReader(stdin)),
                new PrintWriter(sink, true),
                passwordSrc);
    }

    @Test
    public void test_prompt_string_returns_first_valid() {
        StringWriter sink = new StringWriter();
        Prompter p = prompter("alice\n", () -> { throw new IllegalStateException(); }, sink);

        String got = p.promptString("Login", Validators::login);
        assertThat(got).isEqualTo("alice");
        assertThat(sink.toString()).contains("Login");
    }

    @Test
    public void test_prompt_string_retries_up_to_three_times_then_throws() {
        StringWriter sink = new StringWriter();
        Prompter p = prompter("Bad\nAlsoBad\nStillBad\n", () -> { throw new IllegalStateException(); }, sink);

        try {
            p.promptString("Login", Validators::login);
            fail("expected ValidationFailedException");
        } catch (Prompter.ValidationFailedException e) {
            assertThat(e.field()).isEqualTo("login");
        }
    }

    @Test
    public void test_prompt_string_succeeds_on_third_try() {
        StringWriter sink = new StringWriter();
        Prompter p = prompter("Bad\nAlsoBad\nfine\n", () -> { throw new IllegalStateException(); }, sink);

        String got = p.promptString("Login", Validators::login);
        assertThat(got).isEqualTo("fine");
    }

    @Test
    public void test_prompt_password_requires_match() {
        StringWriter sink = new StringWriter();
        Deque<char[]> entries = new ArrayDeque<>();
        entries.add("first".toCharArray());
        entries.add("second".toCharArray());  // mismatch
        entries.add("retry".toCharArray());
        entries.add("retry".toCharArray());   // match

        Prompter p = prompter("", entries::pollFirst, sink);
        String got = p.promptPassword();
        assertThat(got).isEqualTo("retry");
    }

    @Test
    public void test_confirm_yes() {
        StringWriter sink = new StringWriter();
        Prompter p = prompter("y\n", () -> { throw new IllegalStateException(); }, sink);
        assertThat(p.confirm("Really?")).isTrue();
    }

    @Test
    public void test_confirm_no_default() {
        StringWriter sink = new StringWriter();
        Prompter p = prompter("\n", () -> { throw new IllegalStateException(); }, sink);
        assertThat(p.confirm("Really?")).isFalse();
    }

    @Test
    public void test_confirm_no_explicit() {
        StringWriter sink = new StringWriter();
        Prompter p = prompter("n\n", () -> { throw new IllegalStateException(); }, sink);
        assertThat(p.confirm("Really?")).isFalse();
    }
}
