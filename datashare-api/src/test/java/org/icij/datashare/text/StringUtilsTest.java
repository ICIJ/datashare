package org.icij.datashare.text;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.StringUtils.isEmpty;

public class StringUtilsTest  {

    @Test
    public void is_empty_test() {
        assertThat(isEmpty(null)).isTrue();
        assertThat(isEmpty("")).isTrue();
        assertThat(isEmpty("foo")).isFalse();
    }
}