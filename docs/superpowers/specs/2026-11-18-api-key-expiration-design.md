# API key expiration (#2531)

## Intent

API keys never expire today. A leaked key works forever. Add an expiration date so keys stop working after a while, and let admins enforce a maximum lifetime.

## Goals

- A caller can create a key with an optional expiration date (REST and CLI).
- An admin can set a server-wide max lifetime that caps every key, including keys created before the setting existed.
- An expired key is rejected exactly like an unknown key (401, same "authentication required" body).
- `GET /api/key/:userId` exposes the effective expiration date so the UI can show it.

## Non-goals

- Scope (role/project) on keys: follow-up named in the issue.
- Client UI (date picker, display): separate datashare-client PR.
- Multiple keys per user, key rotation, purge of expired rows.

## Anti-goals

- Silent breakage beyond the 30-day default: keys with an explicit expiration date and no cap behave as requested.
- Leaking whether a key exists but is expired (no distinct error message).

## Decisions (Pierre)

- **Caller picks, server caps.** Expiration is optional per key; `--apiKeyMaxLifetime` caps it.
- **Old keys are capped from their creation date.** Effective expiry = `min(expiration_date, creation_date + cap)`, computed at check time. Turning the cap on immediately disables keys older than the cap.
- **30-day default (Pierre, 2026-11-20).** A key without an expiration date expires 30 days after its creation, for new and existing keys alike: existing keys older than 30 days stop working at deploy. Effective expiry = `min(expiration_date or creation_date + 30 days, creation_date + cap)`.
- **Check lives in `ApiKeyStoreAdapter`** (approach 1 below).

## Approaches

1. **Nullable `expiration_date` column, rule in `ApiKeyStoreAdapter` (picked).** The adapter already sits between `ApiKeyFilter` and the repo and can read settings. Repo stays a plain store; the rule is one testable spot.
2. Column, filter expired keys in the SQL `WHERE` (`JooqApiKeyRepository.get`). Rejected: the cap is app config and would leak into the DB layer; CLI `api-key get` would stop seeing expired keys.
3. Store a per-key lifetime instead of a date. Rejected: the UI and CLI think in dates; adds duration math everywhere.

## Design

- **DB:** migration `053-api-key-expiration-date.yml` adds nullable `expiration_date datetime` to `api_key`. Regenerate jOOQ.
- **Model:** `ApiKey.getExpirationDate()` (nullable `Date`); `DatashareApiKey` gets a constructor taking it. `JooqApiKeyRepository.save` writes it (including in the on-conflict update branch, so re-creating a key resets it); `createApiKey` reads it.
- **Setting:** `apiKeyMaxLifetime`, ISO-8601 duration (`P90D`), no default (unset = no cap). Wired on both CLI surfaces: JOpt (`DatashareCliOptions` + `DatashareCli`) and picocli (`ServerOptions`, next to `--sessionTtlSeconds`).
- **Check:** `ApiKeyStoreAdapter.getLogin` returns `null` when `now > effectiveExpiry`, using `DatashareTime` so tests can freeze time.
- **Create:**
  - REST `PUT /api/key/:userId` accepts an optional JSON body `{"expirationDate": "<ISO-8601 instant>"}`. Empty body still works.
  - CLI `datashare api-key create alice --expiration-date 2027-02-01` (picocli `ApiKeyCreateCommand` only; legacy `-k` stays non-expiring).
  - No expiration given: expiration = now + min(30 days, cap). Requested expiration beyond now + cap, or in the past: 400 (CLI: non-zero exit with message).
- **Read:** `GET /api/key/:userId` returns `{"hashedKey": ..., "expirationDate": <effective expiry or null>}`. Adding a field is backward compatible for the client.

## Left to the builder

- How the expiration flows through `GenApiKeyTask` (extra assisted param on `createGenApiKey`) and the Temporal workflow args.
- Where the cap is parsed (`PropertiesProvider` lookup in the adapter vs. injected value) and where the 400 validation lives (resource vs. task); keep one implementation shared by REST and CLI.
- jOOQ codegen reads the live dev DB: run the migration first.
