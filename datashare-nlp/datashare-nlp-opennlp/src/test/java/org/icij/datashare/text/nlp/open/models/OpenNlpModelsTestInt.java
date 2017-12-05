package org.icij.datashare.text.nlp.open.models;

import com.adobe.testing.s3mock.S3MockRule;
import com.amazonaws.services.s3.AmazonS3;
import org.junit.ClassRule;
import org.junit.Test;

public class OpenNlpModelsTestInt {
    private static final String BUCKET_NAME = "mybucket";
    @ClassRule
    public static S3MockRule S3_MOCK_RULE = new S3MockRule();
    private final AmazonS3 s3Client = S3_MOCK_RULE.createS3Client();

    @Test
    public void test_download_model() throws Exception {


    }
}