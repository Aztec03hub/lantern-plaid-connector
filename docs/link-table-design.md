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
- Every place that keyed on `external_id` / `internal_reference` is rekeyed on links, not only the indexer:
  `PlaidFireflyTransaction.normalizeByTransactionId` (joins a Plaid create to its Firefly transaction; it must match on the
  split's link ids, or a retried create is never a `MatchedTransaction` and gets paired as if new, see "Write rules"),
  `filterFireflyCandidateTransferTxs`, the dead letter key in `processFireflyTransactionUpdates` (first link id, never
  `externalId ?: ""`, which would make every create letter share key "" and overwrite each other), `letterFor` and the
  removal lookup in `reviseDeadLetteredCreates`, `survivingLeg`, `shouldSwapTransfer`, and the investment converter.
- Reads: `GET /api/v1/plaid-links?plaid_transaction_id[]=...` (Firefly takes 1 to 500 ids per call, but the ids travel in the query string and 500 is a 414, so the connector sends at most 50 per call, `PlaidLinksApi.MAX_IDS`)
  gives journal and group ids. The POST response carries `plaid_links` on every split (contract). That a group READ
  (`GET /transactions/{id}` and the window list) carries them too is not stated in the contract; the fork's
  `TransactionGroupTransformer` was changed for it, and the live test must assert it before the window list is trusted
  as an index.
- Client: `PlaidLink`/`PlaidLinkLeg` models, `plaid_links` on `TransactionSplit` (read/store) and `TransactionSplitUpdate`,
  and a `PlaidLinksApi` for the lookup. On the update model `plaid_links` MUST be omitted when null (`NON_NULL`):
  the contract treats `"plaid_links": null` as "remove all links", so a serializer that writes nulls would strip every
  link on an ordinary Plaid modify. A unit test asserts the JSON of a modify has no `plaid_links` key.

## What the link table makes redundant (removed)

| Removed | Why |
|---|---|
| `FireflyTransactionExternalIdIndexer` (external_id and transfer `internal_reference` index) | replaced by `PlaidLinkIndexer`, same job, keyed on links |
| `fetchMissingByPlaidId`: two Firefly searches per id (`external_id_is`, `internal_reference_is`) | one batched link lookup plus one group read per hit |
| The DEDUPE role of the dated range read for creates older than the pull window | a retried create is refused by the database (409). The read itself STAYS as the pairing pool for old creates, see "What stays" |
| Firefly's content hash as a dedupe for Plaid creates: `errorIfDuplicateHash = true` and the `422 "duplicate of transaction"` skip in `optimisticInsertBatchIntoFirefly` | the link table is the dedupe. The hash covers the whole submitted row (`TransactionJournalFactory::hashArray`), so while `external_id` was written it never confused two Plaid ids; once `external_id` is no longer written, two real identical purchases (same day, merchant, amount, tags) could hash alike and the second would be skipped. Plaid creates therefore send `errorIfDuplicateHash = false`. The initial-balance insert of batch mode keeps `true`, see "Batch mode" |
| `alreadyRecorded` in `convertPollSync` (skip a create whose id Firefly already records) | the database says so (409), see "Write rules" for the partial-conflict case |
| Role rule for transfer ids, `survivingLeg` by external id, id swaps in `transferLegUpdate`/`convertDoubleFirefly` | `leg` says which leg survives or flips |
| Dead letter pre-check "is the create's external id already in Firefly" in `retryDeadLetters` | a retried create that already landed is a 409 |
| Investments: window read plus `knownExternalIds` set, and reliance on content-hash 422 for older ones | one link lookup of the 14-day re-read (chunked at 50); unknown ids are created |

## What stays

- **Cursor ordering**: cursors move only after Firefly accepted the writes (`commitCursors`), working copy of cursors.
- **Per-Item failure isolation**, Item status store, result callback, `partial` statuses.
- **Pagination restart** and the 200,000 range ceiling.
- **Dead letters for writes Firefly genuinely rejects** (4xx other than 401/403/408/409/429), attempts cap, abandoned,
  unreadable file handling, `reviseDeadLetteredCreates`. One change: **409 is never a dead letter** (see "Write rules").
- **The pull-window list read** (`existingFireflyPullWindowDays`) as the candidate pool for pairing a new Plaid leg with an
  already-imported single or a manual transaction, and `TransferMatcher` itself. The link table dedupes; it does not
  decide which two transactions are one transfer.
- **The dated range read for creates older than the window** (`oldCreateDates`, `fetchFireflyTransactionsBetween`), now
  only as pairing candidates. A link lookup is keyed by the id being looked up, so it can only find a create's OWN
  record, never the other leg it should pair with; without the range read, a history import of a second bank
  (`importHistoryOnFirstSync`) would import every old transfer to an already-imported bank as an unpaired
  withdrawal plus deposit.
- Pending tag, pending candidates excluded from pairing, sign-flip conversion, `preserveUserFields`, `both legs flip`
  review rule (`shouldSwapTransfer`), `transfersNeedingReview`.

## Startup guard

`SyncHelper.setApiCreds` (used by polled and batch) calls `GET /plaid-links?plaid_transaction_id[]=lantern-startup-probe`
after the version check. 200 with a JSON object whose `data` is an array = ok. 404 or 405 (a stock Firefly has no such
route), or a 200 body that cannot be read as that JSON (an HTML page after a redirect, for example: Ktor raises
`NoTransformationFoundException` for a non-JSON content type and `ContentConvertException` for JSON of the wrong shape) = `IllegalStateException("... does not have /api/v1/plaid-links ... run Lantern's Firefly
fork")`, which is not a network error, so `retryWhileNetworkDown` does not retry it and the process stops. 401/403
propagate as before; 5xx and network errors are transient and are retried by `retryWhileNetworkDown`.

A stock Firefly ignores the unknown `plaid_links` field and answers 200, so a Firefly swapped for a stock image while
the connector runs would silently store creates with no links and no hash dedupe. The polled loop therefore repeats the
probe at the start of every iteration (`processTransactions`, before the dead letter retry and any other write), so a
swap fails the poll before anything is written. Every successful POST and every PUT that sent `plaid_links` also checks
that the response's split carries a `plaid_links` array containing the sent ids; if it does not, the iteration fails
with the same `IllegalStateException` message (no extra request). That check alone could not stop the damage: the
create is already stored when it fails, and the next poll would store it again.

## Write rules (one place decides each outcome)

The decision lives in the shared Firefly write path in `SyncHelper` (insert and update), not in
`FireflyTransactionService.guarded`, because batch mode (`BatchSyncRunner`), investments (`syncInvestments`) and the
initial balance call `optimisticInsertBatchIntoFirefly` directly. `guarded` checks 409 BEFORE its "4xx = dead letter"
branch, so `retryDeadLetters` and `processFireflyTransactionUpdates` get the same outcome.

- Create: `POST /transactions` with `plaid_links`, `errorIfDuplicateHash=false`.
- **409 on create**: the body's `conflicts` list EVERY conflicting id of the request (contract). Let `sent` be the request's
  link ids and `conflicted` the listed ones.
  - `sent` minus `conflicted` empty: **already imported**. INFO log, no dead letter, not counted as created, the letter
    (if this was a retry) is dropped.
  - Not empty (a transfer POST `[A, B]` where only A is already in Firefly, for example A imported alone by an iteration
    that then failed, or by batch mode): nothing was written, so B is NOT in Firefly. The write is re-issued for the
    unconflicted legs only: each as its own single create with a `single` link (which itself may 409, and is then
    already imported). Dropping it would commit the cursor over B and lose that money for good.
- **409 on update** is NOT "already imported" in general; nothing of the PUT was applied. By kind:
  - pairing PUT (adds the new leg's id to an existing group): the new leg is already recorded elsewhere; the existing
    group stays as it was. No money is lost or doubled. The converter skips a pairing whose new id the index already
    holds, so this too is only a race: ERROR log (every update 409 is logged at ERROR with Firefly's conflict list).
  - pending to posted PUT: the posted id is already recorded on another transaction, so promoting would leave the pending
    group as a second record of the same money. The converter therefore never promotes when the posted id is already in
    the index (every create that carries a `pending_transaction_id` is looked up with the posted id too): the posted create
    is then "already imported" and Plaid's `removed` event for the pending id is NOT shielded, so the pending group is
    deleted (that frees the pending id). Only a race between the lookup and the PUT can still produce a 409 on this PUT:
    ERROR log naming the group, no dead letter (the next removal event or a person resolves it).
  - any other PUT carrying links (leg flip, one leg removed) only re-sends ids already on this journal, which the contract
    says never conflict, so a 409 there should not happen: ERROR log with the conflicts, no dead letter.
- 422 / other 4xx: dead letter, as today, with one exception: a pairing PUT rejected with 422 or 404 (the existing group
  was deleted by the user) is not dead-lettered as an update, because a group-keyed update letter hides the new leg's
  Plaid id from `reviseDeadLetteredCreates` (a later Plaid remove of that leg would not be applied, and the retry would
  bring it back). Instead the new leg is created as its own single (guarded as a create, so a rejection there becomes a
  create letter keyed by its id). A pending to posted PUT that gets 404 is likewise replaced by a plain create of the
  posted transaction, but on a 422 it is dead-lettered as an update: the pending group still holds that money and this
  sync's removal of the pending id was shielded (`promotedFireflyIds`), so a posted create beside it would count the
  money twice. The two are told apart by the update's type (a pairing PUT is a transfer).
- 5xx / network: fail the iteration, nothing is committed, the next poll redoes it (the whole request is one database
  transaction, contract).
- Update: `PUT /transactions/{group}` with `plaid_links` ONLY when the set changes (pending to posted, pairing, leg flip,
  one leg removed). Omitted means unchanged, so an ordinary Plaid modify sends no links. The connector PUTs a single split
  and only to a group with exactly ONE journal: the fork fills in `transaction_journal_id` for that case
  (`app/Repositories/TransactionGroup/TransactionGroupRepository.php` lines 397-399 at 5d3cb445c4), and the split still sends the journal id it read, as the contract asks. A group
  the user has split into several journals is never PUT with one split (Firefly would destroy the other journals and
  their links); such a target is logged at ERROR and counted in `transfersNeedingReview`.

## Cases

| Case | Behaviour | Contract rule used |
|---|---|---|
| **New create** | POST one split, links `[{id, single, account}]`. | 200 |
| **Retried create** (the iteration failed after some writes, cursor not committed, Plaid resends) | A create whose id is linked to a single in the window list is joined to it by `normalizeByTransactionId` (a `MatchedTransaction`, no request). Anything else is sent and the 409 is "already imported". A retried transfer create whose ids are BOTH linked conflicts on both and is dropped; if only one is linked, the other leg is created as a single (see "Write rules"). | 409 = id on a different transaction, every conflict listed, nothing written |
| **Pending to posted** | Posted create carries `pending_transaction_id`. The pending id is looked up (window, else link lookup + group read). Found: one PUT of that group with `plaid_links` = its existing links with the pending id replaced by the posted id (same leg and account) and the posted fields; the group keeps its id, categories, notes, user tags. If the posted id is already in the index the PUT is not made at all and the pending group is deleted by Plaid's removal (see "Write rules"). Not found: plain create. Plaid's later `removed` event for the pending id must not delete the promoted group: `promotedFireflyIds` still guards it within the same sync (only for a group that is actually promoted), and across syncs the pending id is no longer linked, so the lookup finds nothing (INFO, not ERROR: it is the normal outcome after a retry). | PUT replaces a link atomically; the old id is freed |
| **Plaid modify** | Id found through the index: PUT with the new amount/date/tags, no `plaid_links`. Not found: ERROR log, as today. | key omitted = links unchanged |
| **Plaid remove, single** | DELETE the group; the id is free again. Not found: INFO (a retried removal already freed it). | delete frees ids |
| **Transfer, both legs new in one sync** | `TransferMatcher` pairs them; ONE POST, links `[{src, source, acct}, {dst, destination, acct}]`. Atomic: either both links and the journal exist or nothing does. | one transaction, two links |
| **Transfer, legs arrive in different syncs, either order** | The first leg is imported as a single (pending legs and legs of other types are not paired). When the second arrives the matcher pairs it with the first, found in the window list or, if older, in the dated range read of old creates. One PUT of the first leg's group: `type=transfer`, both accounts, `plaid_links = [first leg with its leg role, second leg with the opposite role]`. The first leg's role comes from its type (withdrawal = source, deposit = destination), its account from its existing link. A manual (unlinked) first leg contributes no link: `plaid_links = [second leg]`. | PUT sets the FULL link set; a new id is added, an existing id keeps its row |
| **Transfer, one leg fails** | A failing POST or PUT (5xx, network) is atomic in Firefly, so nothing half exists; the iteration fails and the next poll redoes the same create or conversion. A 422 on a transfer POST dead-letters the whole create (key = first link id); `reviseDeadLetteredCreates` still applies later Plaid events to it, identifying its legs by `split.plaid_links`. A 422/404 on a pairing PUT creates the new leg as a single instead (see "Write rules"). | whole request is one DB transaction |
| **One leg of a transfer removed** | Index finds the transfer by the removed id. The surviving link's `leg` says what it becomes: destination leg survives = deposit, source leg survives = withdrawal. One PUT: `type`, the unknown side named "Unknown Transfer Source/Recipient", `plaid_links = [survivor as single]` (this also frees the removed id). Both legs removed in one sync = one DELETE. A transfer with only ONE link (paired with a manual transaction) whose Plaid leg is removed is not deleted: it becomes a single on the manual side (the account that is not the removed leg's), `plaid_links = []`, so the user's own record stays. The other bank's money is never deleted. A new amount/date for the survivor in the same sync is kept. | `plaid_links` set replaces; the removed id is freed |
| **Both legs flip direction** | Unchanged `shouldSwapTransfer` rule (only when both flip in one sync, else review). The PUT also swaps the `leg` of both links (`source` <-> `destination`). | PUT full link set |
| **Dead-lettered create, then Plaid event** | `reviseDeadLetteredCreates` unchanged in behaviour; it finds the letter whose key or `split.plaid_links` holds the id. | none |
| **Investment transactions** | Link lookup of the ids read in the lookback (chunked at 50); create the unknown ones with a `single` link (`investment_transaction_id` is the Plaid id). A 409 is already imported. | lookup, 409 |
| **Batch mode** | Creates carry links; a 409 follows the create rules above (a partial conflict creates the other leg). The initial-balance insert has no Plaid id, so no link can protect it: it keeps `errorIfDuplicateHash = true` and the "duplicate of transaction" 422 skip for that one insert, so re-running batch mode after a failure does not add a second opening balance. | 409 |

## Behaviour change to flag

Transactions that an OLDER build created carry `external_id` but no link, so this build does not recognise them (a
re-sync would import them again). Phil's Firefly has no connector data yet (R2/R3 statements); confirm before go-live
that no `external_id_starts:plaid-` transactions exist, or run a one-off backfill. The same holds for a dead letter file
written by an older build (letters with no `plaid_links`): confirm it is empty or absent before go-live; a create letter
without links is logged at ERROR and abandoned rather than sent unprotected.

## Test plan

Unit: each row above, with mock Firefly responses (lookup, group read, 409 body, startup probe 404 and HTML 200), plus:
a transfer POST 409 that lists only one of its two ids creates the other leg; a pending to posted PUT 409 deletes the
pending group and the same-sync removal does not shield it; a pairing PUT 422/404 creates the new leg; a modify PUT's
JSON has no `plaid_links` key; a POST 200 whose response lacks `plaid_links` fails the iteration; batch re-run does not
add a second initial balance; a one-link transfer whose leg is removed becomes a single, not a delete; an old create
outside the window still pairs. Live: own compose project `lantern-connector-lt`, port 8119, postgres:16-alpine,
Firefly image built from the fork at 5d3cb445c4; includes retry-after-crash (a poll that fails between two creates,
then re-run) and the two-bank transfer in both orders, a one-leg removal, pending to posted, the window list read
carrying `plaid_links`, and the stock-Firefly startup failure (the stock `fireflyiii/core:version-6.7.7` image on a
second port). Mutations with `tools/mutrun-kotlin.sh`.
