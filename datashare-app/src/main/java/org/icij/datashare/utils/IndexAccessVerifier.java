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
    /** An index name: dots allowed inside, never leading, so the elasticsearch system indices (.kibana, .security) stay out of reach. */
    private static final String INDEX_NAME = "[-a-zA-Z0-9_]+(\\.[-a-zA-Z0-9_]+)*";
    private static final Pattern INDICES = Pattern.compile("^" + INDEX_NAME + "(," + INDEX_NAME + ")*$");
    /** Suffix of an index derived from a project: "myproject.entities" is granted to whoever is granted "myproject". */
    private static final String ENTITIES_SUFFIX = ".entities";

    static public String checkIndices(String indices) {
        if( indices == null) {
            throw new IllegalArgumentException("indices is null");
        }
        Matcher matcher = INDICES.matcher(indices);
        if( !matcher.matches()) {
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

    /** True for an async-search submit: "<index>/_async_search" (index in pathParts[0]). */
    public static boolean isAsyncSearchSubmit(String path) {
        String[] pathParts = path.split("/");
        return pathParts.length >= 2 && "_async_search".equals(pathParts[1]);
    }

    /** True for an async-search poll/cancel: "_async_search/<id>" (no index segment). */
    public static boolean isAsyncSearchStatusPath(String path) {
        String[] pathParts = path.split("/");
        return pathParts.length >= 2 && "_async_search".equals(pathParts[0]);
    }

    /** The opaque ES async id: everything after the leading "_async_search/". */
    public static String asyncSearchId(String path) {
        if (path == null || !path.startsWith("_async_search/")) {
            throw new IllegalArgumentException("Not an async-search status path: '" + path + "'");
        }
        return path.substring("_async_search/".length());
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
        boolean isAsyncSearchPath = "_async_search".equals(pathParts[1]);
        boolean areAllIndexesGranted = stream(indexes).map(IndexAccessVerifier::baseProject).allMatch(currentUser::isGranted);
        return areAllIndexesGranted && (isMethodGet || isSearchPath || isCountPath || isAsyncSearchPath);
    }

    /**
     * The project an index name authorizes against: itself, or the base project of a known suffix.
     * Exact match on the remainder, never a prefix match, so "myproject-other" doesn't inherit "myproject".
     */
    public static String baseProject(String index) {
        return index.endsWith(ENTITIES_SUFFIX) ? index.substring(0, index.length() - ENTITIES_SUFFIX.length()) : index;
    }

    public static String getUrlString(Context context, String s) {
        if (context.query().keyValues().size() > 0) {
            s += "?" + getQueryAsString(context.query());
        }
        return s;
    }

    static String getQueryAsString(final Query query) {
        return query.keyValues().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
    }
}