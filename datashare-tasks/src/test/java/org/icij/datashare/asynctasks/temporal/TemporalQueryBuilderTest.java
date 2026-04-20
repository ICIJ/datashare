package org.icij.datashare.asynctasks.temporal;

import junit.framework.TestCase;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class TemporalQueryBuilderTest extends TestCase {


    @Test
    public void testFromNamePatternWithMultiplePatterns() {
        assertThat(TemporalQueryBuilder.fromNamePattern("abc|wild.*")).isEqualTo("WorkflowType = 'abc' OR WorkflowType STARTS_WITH 'wild'");
    }

    @Test
    public void testFromNamePatternSinglePattern() {
        assertThat(TemporalQueryBuilder.fromNamePattern("abc")).isEqualTo("WorkflowType = 'abc'");
    }

    @Test
    public void testEmptyNamePattern() {
        assertThrows(RuntimeException.class, () -> TemporalQueryBuilder.fromNamePattern(""));
    }
    @Test
    public void testEmptyNamePatternWithinRegexp() {
        assertThrows(RuntimeException.class, () -> TemporalQueryBuilder.fromNamePattern("abc| "));
    }



}