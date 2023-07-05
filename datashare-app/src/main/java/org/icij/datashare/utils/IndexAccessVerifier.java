package org.icij.datashare.utils;

import net.codestory.http.Context;
import net.codestory.http.Query;
import net.codestory.http.errors.UnauthorizedException;
import org.icij.datashare.session.DatashareUser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.join;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

public class IndexAccessVerifier {

    public String checkIndices(String indices) {
        if( indices == null) {
            throw new IllegalArgumentException("indices is null");
        }
        Pattern pattern = Pattern.compile("^[-a-zA-Z0-9_]+(,[-a-zA-Z0-9_]+)*$");
        Matcher matcher = pattern.matcher(indices);
        if( !matcher.find()) {
            throw new IllegalArgumentException("Bad format for indices : '" + indices+"'");
        }
        return indices;
    }

    public String checkPath(String path, Context context) {
        String[] pathParts = path.split("/");
        if(pathParts.length < 2){
            throw new IllegalArgumentException(String.format("Invalid path: '%s'", path));
        }
        if ("_search".equals(pathParts[0]) && "scroll".equals(pathParts[1])) {
            return getUrlString(context, path);
        }
        String[] indexes = this.checkIndices(pathParts[0]).split(",");
        if (stream(indexes).allMatch(index -> ((DatashareUser)context.currentUser()).isGranted(index)) &&
                ("GET".equalsIgnoreCase(context.method()) ||
                        "_search".equals(pathParts[1]) ||
                        "_count".equals(pathParts[1]) ||
                        (pathParts.length >=3 && "_search".equals(pathParts[2])))) {
            return getUrlString(context, path);
        }
        throw new UnauthorizedException();
    }

    static String getUrlString(Context context, String s) {
        if (context.query().keyValues().size() > 0) {
            s += "?" + getQueryAsString(context.query());
        }
        return s;
    }

    static String getQueryAsString(final Query query) {
        return join("&", query.keyValues().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(toList()));
    }
}