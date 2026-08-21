package org.icij.datashare.utils;

import net.codestory.http.Context;
import net.codestory.http.Query;
import net.codestory.http.errors.UnauthorizedException;
import org.icij.datashare.session.DatashareUser;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Arrays.stream;

public class IndexAccessVerifier {
    /** An index name: a dot separates namespaced segments, and neither a dot nor an underscore may lead.
     *  A leading dot names an elasticsearch system index (.kibana, .security) and a leading underscore
     *  an elasticsearch selector ("_all"), both of which reach far past the one project a caller is
     *  granted. Refusing them here rather than downstream keeps the refusal independent of who is
     *  granted what: a project row named "_all" is grantable, so a grant check alone would let it pass. */
    private static final String INDEX_NAME = "[-a-zA-Z0-9][-a-zA-Z0-9_]*(\\.[-a-zA-Z0-9_]+)*";
    private static final Pattern INDICES = Pattern.compile("^" + INDEX_NAME + "(," + INDEX_NAME + ")*$");
    /** Suffix of an index derived from a project: "myproject.entities" is granted to whoever is granted "myproject". */
    private static final String ENTITIES_SUFFIX = ".entities";

    static public String checkIndices(String indices) {
        if( indices == null) {
            throw new IllegalArgumentException("indices is null");
        }
        if( !INDICES.matcher(indices).matches()) {
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
        String indexSegment = checkIndices(pathParts[0]);
        if (isAuthorizedRequest(context, pathParts, indexSegment)) {
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

    static private boolean isAuthorizedRequest(Context context, String[] pathParts, String indexSegment) {
        DatashareUser currentUser = (DatashareUser) context.currentUser();
        boolean isMethodGet = "GET".equalsIgnoreCase(context.method());
        boolean isSearchPath = "_search".equals(pathParts[1]);
        boolean isCountPath = "_count".equals(pathParts[1]);
        boolean isAsyncSearchPath = "_async_search".equals(pathParts[1]);
        boolean areAllIndexesGranted = baseProjects(indexSegment).stream().allMatch(currentUser::isGranted);
        return areAllIndexesGranted && (isMethodGet || isSearchPath || isCountPath || isAsyncSearchPath);
    }

    /** The projects a comma-separated index list authorizes against, in the same order: each index itself,
     *  or the base project it derives from. Suffix match on the whole remainder, never a prefix match,
     *  so "myproject-other" doesn't inherit "myproject". */
    public static List<String> baseProjects(String indices) {
        return stream(indices.split(",")).map(i -> i.endsWith(ENTITIES_SUFFIX) ? i.substring(0, i.length() - ENTITIES_SUFFIX.length()) : i).toList();
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