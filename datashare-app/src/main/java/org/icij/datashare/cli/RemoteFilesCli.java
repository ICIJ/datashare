package org.icij.datashare.cli;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.icij.datashare.io.RemoteFiles;

import java.io.File;

public class RemoteFilesCli {
    private static RemoteFiles remoteFiles = null;

    public static void main(String[] args) throws Exception {
        OptionSet cmd = parseArgs(args);
        if (RemoteFilesCli.remoteFiles == null) {
            RemoteFilesCli.remoteFiles = cmd.has("a") ? RemoteFiles.getAuthenticated(): RemoteFiles.getDefault();
        }
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
        OptionParser parser = new OptionParser("udaD:f:");
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
        System.out.println("usage : remote-files [-u|-d] [-D remoteDirectory] -f file1 -f file2...");
        System.out.println("-u: to upload content to remote destination");
        System.out.println("-d: to download content from remote destination");
        System.out.println("-a: to use authentication (see DefaultAWSCredentialsProviderChain)");
        System.out.println("-D: remote directory (default : /)");
        System.out.println("-f: directories or files. For directory, the content will be uploaded recursively");
        System.out.println("example : copy-remote -u -D foo -f bar.txt -f qux/ ");
    }

    static void setRemoteFiles(RemoteFiles remoteFiles) { RemoteFilesCli.remoteFiles = remoteFiles;}
}

