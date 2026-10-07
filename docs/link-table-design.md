# Design: dedupe on Firefly's Plaid link table

Branch `link-table`, from `lantern-integration` 28b8e69. Spec: `lantern/docs/core-plaid-links.md` (Firefly fork 5d3cb445c4).
This replaces the app-level "every write is safe to repeat" scheme of review rounds 1-3 with the database guarantee:
Firefly now refuses a second row for the same Plaid transaction id, and tells us which transaction holds it.

## The identity of a Plaid transaction in Firefly

- Before: `external_id = "plaid-" + id`, plus `internal_reference = "plaid-" + id` for the second leg of a transfer, found by
  Firefly search or by a list read. Nothing enforced uniqueness.
- After: a **link** `{plaid_transaction_id: <raw Plaid id, no prefix>, leg: single|source|destination, plaid_account_id}`
  on the split. The connector stops writing `external_id` and `internal_reference` (the fields stay the user's).
  A transfer is ONE transaction with TWO links. Which leg is which is now the `leg` field, so the "destination leg's id is
  the external id" role rule and every id swap that kept it consistent go away.
- Reads: `GET /api/v1/plaid-links?plaid_transaction_id[]=...` (up to 500 ids per call) gives journal and group ids; a
  transaction read (`GET /transactions/{id}`, or the window list) carries `plaid_links` on every split.

## What the link table makes redundant (removed)

| Removed | Why |
|---|---|
| `FireflyTransactionExternalIdIndexer` (external_id and transfer `internal_reference` index) | replaced by `PlaidLinkIndexer`, same job, keyed on links |
| `fetchMissingByPlaidId`: two Firefly searches per id (`external_id_is`, `internal_reference_is`) | one batched link lookup plus one group read per hit |
| The dated range read for creates older than the pull window (`oldCreateDates`, `maxHistoryPages`) | a retried create is refused by the database (409); pairing candidates that are old are found by link lookup |
| Firefly's content hash as a dedupe: `errorIfDuplicateHash = true` and the `422 "duplicate of transaction"` skip in `optimisticInsertBatchIntoFirefly` | it also rejected two real, identical purchases (same day, merchant, amount) and the connector then SKIPPED the second one silently. Now `errorIfDuplicateHash = false`: the link table is the dedupe and it cannot confuse two different Plaid ids |
| `alreadyRecorded` in `convertPollSync` (skip a create whose id Firefly already records) | the database says so (409), see below |
| Role rule for transfer ids, `survivingLeg` by external id, id swaps in `transferLegUpdate`/`convertDoubleFirefly` | `leg` says which leg survives or flips |
| Dead letter pre-check "is the create's external id already in Firefly" in `retryDeadLetters` | a retried create that already landed is a 409 |
| Investments: window read plus `knownExternalIds` set, and reliance on content-hash 422 for older ones | one link lookup of the 14-day re-read; unknown ids are created |

## What stays

- **Cursor ordering**: cursors move only after Firefly accepted the writes (`commitCursors`), working copy of cursors.
- **Per-Item failure isolation**, Item status store, result callback, `partial` statuses.
- **Pagination restart** and the 200,000 range ceiling.
- **Dead letters for writes Firefly genuinely rejects** (4xx other than 401/403/408/429), attempts cap, abandoned, unreadable
  file handling, `reviseDeadLetteredCreates`. One change: **409 is never a dead letter** (it means "already imported").
- **The pull-window list read** (`existingFireflyPullWindowDays`) as the candidate pool for pairing a new Plaid leg with an
  already-imported single or a manual transaction, and `TransferMatcher` itself. The link table dedupes; it does not
  decide which two transactions are one transfer.
- Pending tag, pending candidates excluded from pairing, sign-flip conversion, `preserveUserFields`, `both legs flip`
  review rule (`shouldSwapTransfer`), `transfersNeedingReview`.

## Startup guard

`SyncHelper.setApiCreds` (used by polled and batch) calls `GET /plaid-links?plaid_transaction_id[]=lantern-startup-probe`
after the version check. 200 with a `data` array = ok. 404 or 405 (a stock Firefly has no such route) or a body without
`data` = `IllegalStateException("... does not have /api/v1/plaid-links ... run Lantern's Firefly fork")`, which is not a
network error, so `retryWhileNetworkDown` does not retry it and the process stops. Other statuses (401, 5xx) propagate as before.

## Write rules (one function decides each outcome)

- Create: `POST /transactions` with `plaid_links`, `errorIfDuplicateHash=false`.
- 409 on create or update: **already imported**. INFO log, no dead letter, not counted as created, the letter (if this was a
  retry) is dropped. The body is read only to log the conflicting ids.
- 422 / other 4xx: dead letter, as today. 5xx / network: fail the iteration, nothing is committed, the next poll redoes it.
- Update: `PUT /transactions/{group}` with `plaid_links` ONLY when the set changes (pending to posted, pairing, leg flip,
  one leg removed). Omitted means unchanged, so an ordinary Plaid modify sends no links.

## Cases

| Case | Behaviour | Contract rule used |
|---|---|---|
| **New create** | POST one split, links `[{id, single, account}]`. | 200 |
| **Retried create** (the iteration failed after some writes, cursor not committed, Plaid resends) | If the id is already in the window or looked-up index, the converter skips it with no request. Otherwise it POSTs and the 409 is "already imported". Same for a retried transfer create: both ids conflict on one group. | 409 = id on a different transaction; nothing written |
| **Pending to posted** | Posted create carries `pending_transaction_id`. The pending id is looked up (window, else link lookup + group read). Found: one PUT of that group with `plaid_links` = its existing links with the pending id replaced by the posted id (same leg and account) and the posted fields; the group keeps its id, categories, notes, user tags. Not found: plain create. Plaid's later `removed` event for the pending id must not delete the promoted group: `promotedFireflyIds` still guards it within the same sync, and across syncs the pending id is no longer linked, so the lookup finds nothing. | PUT replaces a link atomically; the old id is freed |
| **Plaid modify** | Id found through the index: PUT with the new amount/date/tags, no `plaid_links`. Not found: ERROR log, as today. | key omitted = links unchanged |
| **Plaid remove, single** | DELETE the group; the id is free again. | delete frees ids |
| **Transfer, both legs new in one sync** | `TransferMatcher` pairs them; ONE POST, links `[{src, source, acct}, {dst, destination, acct}]`. Atomic: either both links and the journal exist or nothing does. | one transaction, two links |
| **Transfer, legs arrive in different syncs, either order** | The first leg is imported as a single (pending legs and legs of other types are not paired). When the second arrives the matcher pairs it with the first, found in the window list or, if older, by link lookup of pair-capable creates. One PUT of the first leg's group: `type=transfer`, both accounts, `plaid_links = [first leg with its leg role, second leg with the opposite role]`. The first leg's role comes from its type (withdrawal = source, deposit = destination), its account from its existing link. | PUT sets the FULL link set; a new id is added, an existing id keeps its row |
| **Transfer, one leg fails** | A failing POST or PUT (5xx, network) is atomic in Firefly, so nothing half exists; the iteration fails and the next poll redoes the same create or conversion. A 422 dead-letters the whole write (key = first link id); `reviseDeadLetteredCreates` still applies later Plaid events to it, now identifying its legs by `split.plaid_links`. | whole request is one DB transaction |
| **One leg of a transfer removed** | Index finds the transfer by the removed id. The surviving link's `leg` says what it becomes: destination leg survives = deposit, source leg survives = withdrawal. One PUT: `type`, the unknown side named "Unknown Transfer Source/Recipient", `plaid_links = [survivor as single]` (this also frees the removed id). Both legs removed in one sync = one DELETE. The other bank's money is never deleted. A new amount/date for the survivor in the same sync is kept. | `plaid_links` set replaces; the removed id is freed |
| **Both legs flip direction** | Unchanged `shouldSwapTransfer` rule (only when both flip in one sync, else review). The PUT also swaps the `leg` of both links (`source` <-> `destination`). | PUT full link set |
| **Dead-lettered create, then Plaid event** | `reviseDeadLetteredCreates` unchanged in behaviour; it finds the letter whose key or `split.plaid_links` holds the id. | none |
| **Investment transactions** | Link lookup of the ids read in the lookback; create the unknown ones with a `single` link (`investment_transaction_id` is the Plaid id). | lookup, 409 |
| **Batch mode** | Creates carry links; a 409 is skipped. | 409 |

## Behaviour change to flag

Transactions that an OLDER build created carry `external_id` but no link, so this build does not recognise them (a
re-sync would import them again). Phil's Firefly has no connector data yet (R2/R3 statements); confirm before go-live
that no `external_id_starts:plaid-` transactions exist, or run a one-off backfill.

## Test plan

Unit: each row above, with mock Firefly responses (lookup, group read, 409 body, startup probe 404). Live: own compose
project `lantern-connector-lt`, port 8119, postgres:16-alpine, Firefly image built from the fork at 5d3cb445c4; includes
retry-after-crash (a poll that fails between two creates, then re-run) and the two-bank transfer in both orders, a
one-leg removal, pending to posted, and the stock-Firefly startup failure (the stock `fireflyiii/core:version-6.7.7` image
on a second port). Mutations with `tools/mutrun-kotlin.sh`.
