# ES search proxy: grant checks on HEAD and OPTIONS

Issue: [#2312](https://github.com/ICIJ/datashare/issues/2312)
Branch: `fix/2312-es-proxy-grant-checks` (from `main`)
Date: 2026-08-04

## Problem

Two endpoints on the Elasticsearch search proxy reach Elasticsearch without checking the
caller's project grants. The proxy's authorization lives in `IndexAccessVerifier.checkPath`,
which maps every index in the path to a project and requires `allMatch(currentUser::isGranted)`.
`esPost` and `esGet` call it. These two do not.

### `esHead` (`IndexResource.java:104-110`)

```java
@Head("/search/:path:")
public Payload esHead(final String path) throws IOException {
    return new Payload(indexer.executeRaw("HEAD", path, null));
}
```

The method takes no `Context`, so it cannot see the current user and structurally cannot check
a grant. The caller's path is forwarded verbatim. An authenticated user can probe any index
name, or any document (`HEAD /api/index/search/other-project/_doc/<id>`), and learn from the
response whether it exists.

The disclosure is existence-only, not content. It leaks the project naming of an installation,
and becomes more interesting once `<project>.entities` indices exist (#2205), since the presence
of one reveals that a project has had structured entities extracted.

### `esOptions` (`IndexResource.java:262-275`)

The method signature is `esOptions(final String index, final String path, Context context)`,
but the route `@Options("/search/:path:")` declares **one** parameter. fluent-http resolves this
as follows:

- `UriParser` splits the pattern into `["api", "index", "search", ":path:"]` and counts one part
  starting with `:`, so `paramsCount == 1` and the splat lands in `params[0]`.
- `ReflectionRoute.convert(context, params, types...)` sizes the argument array to the method's
  parameter count, fills index 0 from `params[0]`, then fills every remaining index with
  `context.extract(type)`.
- `Context.extract(String.class)` returns `request.content()`.

So `index` receives the whole splat and `path` receives the **request body**, which is then
handed to `indexer.executeRaw("OPTIONS", path, null)` (`ElasticsearchIndexer.java:233`). The
value that is shape-checked is not the value that is executed: the request body steers an
arbitrary Elasticsearch path, gated only by a grammar check on an unrelated string.

The disclosure is thin, because `executeRaw` returns only the `Allow` header for OPTIONS
(`ElasticsearchIndexer.java:240-242`), but the mechanism is arbitrary path forwarding.

A second defect follows from the same code. With an empty body the forwarded path is `""`,
which `executeRaw` normalises to `/`, so the preflight answers with the `Allow` header of the
Elasticsearch **root**. Measured against the dev cluster:

```
$ curl -s -i -X OPTIONS http://elasticsearch:9200/ | grep -i '^allow'
Allow: GET,DELETE,HEAD
```

That is the wrong method set for this route: it advertises DELETE, which the proxy permits only
on an async-search status path (`esDelete` returns 405 otherwise), and omits POST, which is the
main search verb.

## Scope

The issue originally covered five endpoints. `createIndex`, `_close` and `_open` are being gated
in #2311, because that PR's `<project>.entities` rule is what turns their missing checks from an
untidiness into an escalation. This spec covers only what remains: `esHead` and `esOptions`.

`#2311` is open and has not yet committed those gates (its head `4775eadb6` still has the bare
`checkIndices` in `createIndex`). Leaving those three endpoints alone here is therefore also
what keeps the two branches from colliding.

Out of scope, deliberately:

- `createIndex`, `_close`, `_open`: #2311.
- `createIndexPreflight` (`IndexResource.java:91-98`): already answers a static method list
  without touching Elasticsearch, so there is nothing to leak and nothing to authorize.
- The snapshot endpoints: all gated by `modeVerifier.checkAllowedMode(LOCAL, EMBEDDED)`.

## Design

### 1. One shared grant check in `IndexAccessVerifier`

```java
static public String checkIndicesGranted(String indices, Context context) {
    String[] indexes = checkIndices(indices).split(",");
    DatashareUser currentUser = (DatashareUser) context.currentUser();
    if (!stream(indexes).allMatch(currentUser::isGranted)) {
        throw new UnauthorizedException();
    }
    return indices;
}
```

Shape check first (400 via the existing `checkIndices`), then grants (401 via
`UnauthorizedException`, the same refusal `checkPath` already raises). It returns the validated
string so call sites read like today's `checkIndices` calls.

**Why not reuse `checkPath`.** The issue suggests routing `esHead` through
`IndexAccessVerifier.checkPath(path, context)`. That does not work. `checkPath`
(`IndexAccessVerifier.java:28-40`) bundles the grant check with a path-and-operation policy:

- it requires two or more path segments, so a bare `HEAD /api/index/search/<index>` would 400;
- `isAuthorizedRequest` (`IndexAccessVerifier.java:68-76`) requires `pathParts[1]` to be
  `_search`, `_count` or `_async_search`
  unless the method is GET, so `HEAD /api/index/search/<index>/_doc/<id>` would 401 even for a
  user who holds the grant.

Dropping `checkPath` into `esHead` would disable the endpoint rather than authorize it, and it
would specifically fail to cover the `_doc` probe the issue names. Splitting the grant check out
gives two units with one purpose each: `checkPath` is grants plus the search-proxy operation
policy, `checkIndicesGranted` is grants for an index-addressed endpoint.

`checkPath` itself is left untouched. Its grant test is a single `allMatch` fused into a boolean
expression alongside the operation flags; restructuring a security function under test to save
one line is a bad trade.

**Coordination.** #2311 needs the same grant check for `createIndex`, `_close` and `_open`.
Whichever PR lands second should adopt this helper rather than introduce a second one. The
helper is named for the check it performs, not for a caller, so that it can be reused as is.

### 2. `esHead` takes a `Context`

```java
@Head("/search/:path:")
public Payload esHead(final String path, Context context) throws IOException {
    try {
        IndexAccessVerifier.checkIndicesGranted(path.split("/")[0], context);
        return new Payload(indexer.executeRaw("HEAD", path, null));
    } catch (IllegalArgumentException e) {
        return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
    }
}
```

The leading path segment holds the comma-separated indices, the same segment `checkPath` reads.
Behaviour change: a granted index is unaffected; an ungranted index answers 401 where it
previously answered 200 (index exists) or 500 (index absent, see follow-ups); a malformed name
still answers 400.

### 3. `esOptions` stops calling Elasticsearch

```java
@Options("/search/:path:")
public Payload esOptions(final String path) {
    if (path != null && IndexAccessVerifier.isAsyncSearchStatusPath(path)) {
        return PayloadFormatter.allowMethods("OPTIONS", "GET", "DELETE");
    }
    try {
        IndexAccessVerifier.checkIndices(path);
        return PayloadFormatter.allowMethods("OPTIONS", "GET", "POST", "HEAD");
    } catch (IllegalArgumentException e) {
        return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
    }
}
```

The second `String` parameter is deleted, and with it the body-as-path forwarding: one route
parameter means one method parameter, so nothing is filled from `Context.extract`. The
`index`/`path` fallback and its explanatory comment go too, since there is no longer a pair to
disambiguate. The `Context` parameter is also dropped, as nothing in the method uses it.

The returned method set is now what the proxy accepts on this route rather than what
Elasticsearch answers for `/`: OPTIONS, GET, POST and HEAD, with DELETE reserved for the
async-search status short-circuit above.

**No grant check here.** A browser preflight is sent without credentials, so a 401 would break
legitimate cross-origin preflights. It is safe to omit because the response is now identical for
every index, leaving nothing to disclose. This matches `createIndexPreflight`, which already
answers a static method list in the same file.

## Testing

TDD, RED before any production code. All new tests get their own `configure(...)`, because
`ProdWebServerRule` is a `@ClassRule` in `AbstractProdWebServerTest` and configuration leaks
between tests in `IndexResourceTest`.

| Test | Assertion | Why it is RED today |
| --- | --- | --- |
| `esHead` refuses an ungranted index | granted `indexNames[0]`, `head("/api/index/search/<indexNames[1]>")` responds 401 | currently 200. Probing an index that **exists** but is not granted proves the grant check rather than the index's absence |
| `esHead` still serves a granted index | `head("/api/index/search/<indexNames[0]>")` responds 200 | guards against the fix disabling the endpoint |
| `esOptions` advertises the route's methods | `Access-Control-Allow-Methods` contains OPTIONS and POST | currently the ES root header `GET,DELETE,HEAD`, which contains neither |

One existing test is renamed: `test_no_auth_options_forward_request_to_elastic` no longer
forwards anything. Its three assertions (200 for a valid name, 400 for the two malformed ones)
hold unchanged.

No fixture repairs are needed. Those were driven by the `createIndex` grant check, which has
moved to #2311. No new index is created by a HEAD or an OPTIONS, so `tearDown` is unchanged.

## Suggested follow-ups

Not in this diff.

- `esHead` answers 500 rather than 404 for a missing index: `executeRaw` raises the ES
  `ResponseException`, which escapes the `catch (IllegalArgumentException)`. Orthogonal to
  authorization, and the existence oracle behaves the same either way.
- The issue asks to be retitled now that its scope is `esHead` and `esOptions` only.
- If `FluentRestTest` cannot attach a body to an OPTIONS request, the Allow-list assertion
  carries that test alone and the body-steering path is covered only by the deleted parameter.
- `checkIndices` on `main` uses `Matcher.find()` with an anchored pattern, so a trailing newline
  slips through. #2311 fixes this with `matches()` (commit `cb80a248b`). It does not weaken
  `checkIndicesGranted`, because `isGranted("granted\n")` is false and the request is refused
  with a 401 rather than a 400.
