package org.icij.datashare;

import org.icij.datashare.extract.RedisUserReportMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;

public class ReportExtractor {
    static Logger logger = LoggerFactory.getLogger(ReportExtractor.class.getName());
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.out.println("usage: report redis-url report-name");
            System.exit(1);
        }

        try (RedisUserReportMap reportMap = new RedisUserReportMap(new PropertiesProvider(new HashMap<>() {{
            put("redisAddress", args[0]);
        }}), args[1])) {
            reportMap.forEach((path, report) -> {
                if (report.getException().isPresent()) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    report.getException().get().printStackTrace(pw);
                    logger.info("\"{}\" \"{}\"", path, sw.toString().replace("\n", "|"));
                }
            });
        }
    }
}
