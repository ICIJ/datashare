package org.icij.datashare.cli;

import com.amazonaws.auth.ClasspathPropertiesFileCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.icij.datashare.io.RemoteFiles;

import java.io.File;

public class RemoteFilesCli {
    static final String S3_DATASHARE_BUCKET_NAME = "s3.datashare.icij.org";
    private static AmazonS3 s3Client = null;

    public static void main(String[] args) throws Exception {
        AmazonS3 amazonS3 = s3Client;
        if (amazonS3 == null) {
            amazonS3 = AmazonS3ClientBuilder.standard().withRegion("us-east-1")
                    .withCredentials(new ClasspathPropertiesFileCredentialsProvider("s3.properties")).build();
        }
        final RemoteFiles remoteFiles = new RemoteFiles(amazonS3, S3_DATASHARE_BUCKET_NAME);

        OptionSet cmd = parseArgs(args);
        String remoteKey = cmd.has("D") ? (String)cmd.valueOf("D") : "/";
        if (cmd.has("u")) {
            for (Object arg : cmd.valuesOf("f")) {
                remoteFiles.upload(new File((String) arg), remoteKey);
            }
        } else if (cmd.has("d")) {
            for (Object arg : cmd.valuesOf("f")) {
                remoteFiles.download(remoteKey, new File((String) arg));
            }
        } else {
            usage();
        }
    }

    private static OptionSet parseArgs(final String[] args) {
        OptionParser parser = new OptionParser("udD:f:");
        OptionSet optionSet = null;
        try {
            optionSet = parser.parse(args);
        } catch (Exception e) {
            usage();
            System.exit(1);
        }
        return optionSet;
    }

    private static void usage() {
        System.out.println("usage : copy-remote [-u|-d] [-D remoteDirectory] -f file1 -f file2...");
        System.out.println("-u: to upload content to remote destination");
        System.out.println("-d: to download content from remote destination");
        System.out.println("-D: remote directory (default : /)");
        System.out.println("-f: directories or files. For directory, the content will be uploaded recursively");
        System.out.println("example : copy-remote -u -D foo -f bar.txt -f qux/ ");
    }

    static void setS3client(AmazonS3 s3) { s3Client = s3;}
}

