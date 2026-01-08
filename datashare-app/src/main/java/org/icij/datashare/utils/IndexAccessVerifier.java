package org.icij.datashare.utils;

import net.codestory.http.Context;
import net.codestory.http.Query;
import net.codestory.http.errors.UnauthorizedException;
import org.icij.datashare.session.DatashareUser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Arrays.stream;

public class IndexAccessVerifier {

    static public String checkIndices(String indices) {
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

    static public String checkPath(String path, Context context) {
        String[] pathParts = path.split("/");
        if (pathParts.length < 2) {
            throw new IllegalArgumentException(String.format("Invalid path: '%s'", path));
        }
        if (isSearchScrollPath(path)) {
            return getUrlString(context, path);
        }
        String[] indexes = checkIndices(pathParts[0]).split(",");
        if (isAuthorizedRequest(context, pathParts, indexes)) {
            return getUrlString(context, path);
        }
        throw new UnauthorizedException();
    }

    static private boolean isSearchScrollPath(String path) {
        String[] pathParts = path.split("/");
        return "_search".equals(pathParts[0]) && "scroll".equals(pathParts[1]);
    }

    static private boolean isAuthorizedRequest(Context context, String[] pathParts, String[] indexes) {
        DatashareUser currentUser = (DatashareUser) context.currentUser();
        boolean isMethodGet = "GET".equalsIgnoreCase(context.method());
        boolean isSearchPath = "_search".equals(pathParts[1]);
        boolean isCountPath = "_count".equals(pathParts[1]);
        boolean isSearchPathAtThirdPosition = (pathParts.length >= 3 && "_search".equals(pathParts[2]));
        boolean areAllIndexesGranted = stream(indexes).allMatch(currentUser::isGranted);
        return areAllIndexesGranted && (isMethodGet || isSearchPath || isCountPath || isSearchPathAtThirdPosition);
    }

    static String getUrlString(Context context, String s) {
        if (context.query().keyValues().size() > 0) {
            s += "?" + getQueryAsString(context.query());
        }
        return s;
    }

    static String getQueryAsString(final Query query) {
        return query.keyValues().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
    }
}