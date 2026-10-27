# Tagging and untagging by query over MCP

## Intent

An agent connected over MCP can tag and untag documents, but only by passing explicit document ids.
Getting those ids is the expensive half: nothing in the MCP surface answers "which documents carry
this tag" or "which documents match this query", so the agent pages `search_documents` fifty hits at
a time and carries thousands of ids through its context. Removing one mistakenly applied tag from a
corpus costs on the order of eighteen thousand tokens, almost all of it ids travelling to the agent
and straight back.

This design lets the selection travel instead of the ids: `tag_documents` and `untag_documents`
accept a query, the server resolves it, and the agent sends about fifty tokens.

## Why now

Two things make this worth building rather than tolerating.

The chore is recurring. Tags get applied by mistake and have to be removed wholesale, regularly, and
the person doing it today reaches for tarentula's `tagging_by_query` and `tag_cleaning_by_query`.
Both talk straight to Elasticsearch with an `_update_by_query` and never call Datashare, so they
leave `document_tag` untouched. Until recently that was nearly invisible, because `list_tags` read
the index. Since `list_tags` and `get_document` were moved onto PostgreSQL, a tag cleaned with
tarentula is gone from search and still present in both MCP tools. The supported path has to be good
enough that nobody needs the unsupported one.

The second reason is that the pieces are now in place. The tools already delegate to
`DocumentResource`, which writes both stores and carries the grant check; the error reporting learned
to say which half of a write landed; and the caps are measured rather than guessed.

## Goals

- Select documents for tagging and untagging with a query instead of a list of ids.
- Keep PostgreSQL and the index in step, as the by-id path does.
- Let an operation larger than one batch continue without the agent holding any state.
- Give the human the size of what is about to happen before any of it happens.

## Non-goals

- No asynchronous task. The realistic query is of the order of ten thousand documents, which the
  measurements put at under two seconds of database write. Queries matching millions exist in these
  corpora, but tagging millions of documents in one gesture is not the workflow being supported.
- No change to the REST routes. `POST /:project/documents/batchUpdate/tag` and its sibling keep their
  current contract and their absence of caps.
- No query syntax of our own. The `query_string` syntax `search_documents` already exposes is what an
  agent has learned to write, and it is what this accepts.
- No divergence reporting in the write response. Comparing what each store did sounded right until
  the units turned out not to match: the database writes one row per document-tag pair while the
  index updates one document, so with more than one tag the two numbers differ on every successful
  call and the signal would fire constantly. Checking the two stores against each other is worth
  doing, on demand rather than on every write, and it is separate work.
- No repair of the divergences tarentula has already left behind.

## Anti-goals

Things that would make this a failure even if it worked:

- An agent that tags a corpus without the size of the operation ever being reported.
- An operation that reports success while leaving part of the selection untouched.
- A loop that an agent can enter and not get out of, or worse, one it leaves early believing it is
  done.

## Constraints

- `index.max_result_window` is 10000. A search cannot return a result beyond the ten-thousandth, so
  a query can be resolved to at most ten thousand ids at a time and there is no deeper page without
  a point in time. This is the constraint the whole batching scheme rests on, and the ten thousand
  document cap measured for the by-id path happens to land exactly on it.
- `index.max_terms_count` is 65536 on these indices, and the batch write selects documents with a
  terms query on `_id`. The ten thousand cap stays well under it.
- The transport is a single POST with no stream, so the server cannot send a request to the client.
  The protocol revision this server speaks has `elicitation/create` for asking the user something,
  and it is unusable here: confirmation has to travel in the tool's own arguments and result.
- `index.refresh_interval` is 1s. A document tagged a moment ago is not yet visible to a query that
  filters on that tag.
- Writes go to PostgreSQL first, then to the index, with no transaction spanning the two.
- `document_tag` holds one row per document and label. There is no tag entity: a tag is its rows.
- The caps measured for the by-id path hold here too: 10000 documents, 100 tags, and 100000
  document-tag pairs per call, beyond which a single insert takes 29 seconds.

## The design

### Selecting with a query

`tag_documents` and `untag_documents` gain a `query` argument, in the same `query_string` syntax as
`search_documents`, for example `contentType:application/pdf AND content:passeport`. It is an
alternative to `doc_ids`, not a companion: exactly one of the two must be given, and supplying both
or neither is an invalid argument.

The server resolves the query to document ids, then performs the write it already performs for an
explicit list. The agent never sees the ids.

### Confirming before writing

A call that selects with a query does nothing on its first pass. It resolves the query, reports how
many documents matched and what it would do to them, and asks whether to go ahead. A second call
repeating the same arguments with `confirmed` set performs the write.

This applies to every query, not only to large ones. A threshold would need a number nobody can
defend, and the point is not that large operations are dangerous while small ones are safe: it is
that the person asking for the work should see its size before it happens. Selecting with `doc_ids`
stays a single call, since the caller already enumerated what it is touching.

What this mechanism actually guarantees is worth stating plainly, because it is less than it looks.
The server cannot know whether a human saw anything. An agent can send the confirmation on its own,
and an agent set on avoiding the question can use `doc_ids`, which has no confirmation at all. What
the two-pass shape does guarantee is that the normal path surfaces the count, and that there is a
moment where the client can stop. A token tying the second call to the first was considered and
dropped: it cannot be made unforgeable without a server secret this codebase does not have, and it
would not close the `doc_ids` route anyway.

### Batches, and how an operation continues

A query can match far more than one batch. When it does, the confirmed call processes the first ten
thousand and says so, returning how many it handled, that more remain, and the call to make next.

The number reported is not exact beyond ten thousand. Elasticsearch only counts hits exactly that
far unless asked to go further, and asking costs more the larger the match set, on corpora where a
single query can match millions. The response therefore says "at least ten thousand remain" rather
than paying for a precise figure. The human decides to continue on the shape of the thing, not on
its exact size.

Continuation carries no state, and this is forced rather than chosen. Since a search cannot page
past the ten-thousandth result, the only way to reach the eleventh thousand document is for the
match set itself to shrink. The tag does that: the next call carries `content:passeport AND NOT
tags:passeport`, and symmetrically `content:passeport AND tags:passeport` for removal, and each
batch leaves a smaller one behind until nothing matches.

Because the scheme breaks silently when the caller gets this wrong, repeating the original query
forever against the same first ten thousand, the response carries the continuation query ready to
use rather than relying on the model to compose it. Each batch is its own query, so each goes
through the same two passes: the human sees every batch before it runs, not only the first.

### Why the index is refreshed

The write forces an index refresh before returning. Without it, an agent calling again within the
second gets the same documents back, because the tag it just applied is not yet visible to
`NOT tags:passeport`. Re-tagging them is harmless, both stores skip what is already there, but the
tool would answer "nothing changed" while thousands of documents remain unprocessed, and the agent
would stop early believing the work was done. Silent incompleteness is the worst of the available
failures, which is why the refresh is not optional.

The cost is small. The index refreshes every second on its own, so forcing it advances an operation
that was about to happen, and its cost follows what was just written rather than the size of the
index.

## Decisions made here, and by whom

Decided in conversation with the requester:

- A query argument on both tools, symmetric for tagging and untagging.
- Batches with an explicit question in the response rather than silent continuation.
- Continuation by self-excluding query rather than a cursor.
- A forced refresh rather than a reported count alone, once the early-stop failure was understood.
- An approximate count rather than an exact one, once its cost on a large corpus was clear.
- Divergence checking moved out of the write response and into separate, on-demand work, once the
  two counts turned out to measure different things.
- Every query confirmed before it writes, with no threshold.
- A plain confirmation rather than a token, once it was clear a token could not be made unforgeable
  here and would not close the `doc_ids` route anyway.

Decided here, as the author's call, and open to correction:

- `query` and `doc_ids` are mutually exclusive rather than combinable.
- The caps are the ones already in force for the by-id path, unchanged.
- The query is resolved in the MCP layer, which keeps the REST routes untouched as the requester
  asked when the caps were placed. Note the consequence: the raw Elasticsearch search path that was
  folded back into `McpSearchDocumentsTool` when it had a single caller now has a second one, and
  extracting it again is reasonable.
- Nothing is done about the divergences already in the data.
- In query mode the server sizes the batch rather than refusing the call. With twenty tags, ten
  thousand documents would exceed the hundred thousand pair budget; since the server is the one
  choosing the batch in this mode, it chooses one that fits.
- The response carries the continuation query, rather than describing the idiom and trusting the
  model to build it.

## Left to the builder

- Where the query resolution lives and how it reuses the existing raw search path.
- The exact shape of the response, including how the question is worded so that a model relays it.
- Whether the resolution asks the index for ids only, and with what page size, given that the cap
  bounds how many are needed.
- Test coverage for the case that drove the design: a query matching more than one batch, continued
  to exhaustion, with the refresh in place.
