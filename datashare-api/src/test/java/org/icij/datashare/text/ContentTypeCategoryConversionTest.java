package org.icij.datashare.text;


import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class ContentTypeCategoryConversionTest {

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {"audio/vorbis", ContentTypeCategory.AUDIO},
                // This one is from the properties file loaded at startup
                {"application/vnd.wordperfect", ContentTypeCategory.DOCUMENT},
                {"application/vnd.ms-outlook", ContentTypeCategory.EMAIL},
                {"image/jpeg", ContentTypeCategory.IMAGE},
                {"application/x-msdownload", ContentTypeCategory.OTHER},
                {"application/vnd.ms-powerpoint", ContentTypeCategory.PRESENTATION},
                {"application/x-msexcel", ContentTypeCategory.SPREADSHEET},
                {"application/mp4", ContentTypeCategory.VIDEO},
                {null, ContentTypeCategory.OTHER},
                {"", ContentTypeCategory.OTHER},
                {"0192092349843", ContentTypeCategory.OTHER},
                {"Unknown///", ContentTypeCategory.OTHER},
        });
    }

    private final String input;
    private final ContentTypeCategory expected;

    public ContentTypeCategoryConversionTest(String input, ContentTypeCategory expected) {
        this.input = input;
        this.expected = expected;
    }

    @Test
    public void test_content_type_conversion() {
        assertEquals(String.format("Unexpected ContentTypeCategory for ContentType \"%s\"", input), expected, ContentTypeCategory.fromContentType(input));
    }
}