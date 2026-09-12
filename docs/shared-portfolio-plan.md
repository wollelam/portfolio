# Shared portfolios: desktop and CLI

Status: first implementation slice in progress, 2026-09-12. The transport,
local session, CLI commands, and desktop menu workflow are implemented; semantic
merging, background aggregation, and a dedicated review UI remain future work.

- Checkout: `/home/ole/source/portfolio-shared-sync`
- Branch: `feature/shared-portfolio-sync`
- Base: `personal/master` at `d4707123c` (includes both desktop and CLI).

## Problem and decision

Opening one portfolio file on several machines through Google Drive permits
an older in-memory client to overwrite another client's saved changes. A
filesystem lock cannot coordinate machines whose disks synchronize later.

Use a Drive-synchronized directory as a mailbox, with **one designated owner
machine** publishing the master and independent local working files on every
client. The protocol is shared by desktop and CLI. It needs no GitHub account,
Git installation, Drive API token, or server. It also works with other folder
sync tools. Each user's sync tool remains responsible for delivery.

GitHub would provide remote compare-and-swap and history, but it would still
require conflict handling for linked financial records. It is a possible
future transport, not a requirement for this implementation.

## Protocol v1

The shared directory contains:

```
workspace.properties           protocol version, workspace ID, owner identity
head                           hash of the owner's current master snapshot
revisions/<sha256>.portfolio    immutable master snapshots
submissions/<uuid>.ppchange     immutable ZIP: metadata + proposed portfolio
accepted/<uuid>                receipt written only by the owner
```

The implementation also writes `master.portfolio` as a convenient current
copy. The authoritative master is the verified snapshot named by `head`;
contributors never open or edit `master.portfolio` directly.

Each local working file has a `.ppsync` sidecar outside the shared directory.
It records the workspace's path on that machine, workspace ID, parent snapshot
hash, and a per-device identity. The owner identity in the workspace metadata
prevents an ordinary contributor session from accepting submissions; it is not
a security boundary against someone with write access to the shared folder.
Do not copy a sidecar to another machine or put it in cloud storage.

Opening a workspace verifies the hash of the referenced snapshot. A pointer
whose snapshot has not arrived yet is reported as an incomplete sync.
Submissions are complete immutable packages, published locally using a temp
file and rename. Readers verify format, workspace ID and payload checksum;
partially delivered or damaged packages cannot become the master.

Save always persists the working file first, retaining the app's encryption,
format and local backup behavior. A contributor then submits that exact file,
including its parent hash. Subsequent submissions form a chain, so the owner
can apply several saves from the same client in order. The owner publishes
directly only if its parent still matches the master.

Applying a submission requires the owner identity, an unchanged owner working
file, a valid envelope/checksum, and a parent matching the current master.
Publishing adds an immutable revision and updates the owner-only pointer. A
receipt makes repeated delivery idempotent. A stale submission stays pending
with its full payload intact. No last-writer-wins rule, text merge or
force-accept exists. The first slice transports portfolio bytes opaquely; model
validation and encrypted-candidate password handling are explicitly deferred
to the coordinator/merge milestone.

The owner must be a single machine. Local locking can serialize processes on
that machine; a Drive file is not a distributed lock. If the owner is offline,
contributors can continue saving/submitting and aggregation waits for it.

## Implementation milestones and acceptance criteria

1. **First usable prototype (implemented in part)**
   - Common folder protocol, persisted local associations and ownership checks.
   - Desktop workspace creation/opening and manually applied owner submissions.
   - CLI equivalents through explicit `SYNC` commands; ordinary `STORE` remains
     a local save followed by `SYNC SUBMIT`.
   - Concurrent submissions survive; only a matching-parent submission applies.
   - Verify restart, out-of-order delivery, corruption, owner checks, local
     recovery on failure, encryption and preservation of existing local workflows.

2. **Desktop review and recovery**
   - Review window with submitter/device, changes, pending/conflicting status,
     and owner acceptance receipts visible to contributors.
   - Refresh a clean working copy; retain a pending copy when refreshing an
     edited one. Resume uploads after interrupted saves; show sync status in tabs.
   - Poll/debounce delivered packages in a background job with cancellation.
   - Journal owner publication and local-file replacement for automatic crash
     recovery. Explicit owner transfer and recovery from a lost owner sidecar.
   - Integrate original master-file migration and explain local vs shared paths.

3. **Semantic aggregation, before automatic conflict resolution**
   - Compare base/local/master models, keyed by stable entity and transaction
     IDs. Audit existing serialization and generate persistent IDs where absent.
   - Merge independent field changes and disjoint new records. Treat transfers,
     buy/sell cross entries and their fees/taxes as indivisible groups.
   - Detect update/update, update/delete, duplicate imports, currency/taxonomy
     changes, incompatible app versions, and price-feed conflicts explicitly.
   - Validate reference integrity, cross entries, balances and consistency
     checks before owner publication. Present unresolved choices for review.
   - Property-based convergence and crash/replay tests; sanitized multi-client
     scenarios covering imports, editing, deletion, quotes and encryption.

4. **Release preparation**
   - Test real Drive delivery across two desktop machines plus CLI, including
     offline changes, delayed/out-of-order uploads and conflict-copy filenames.
   - User-facing translations, accessible dialogs and status indicators.
   - Bounded storage/retention, archive/export recovery and supported format limits.
   - Decide whether to offer an always-running owner service using the same core.

## Boundaries of the first prototype

Changes are whole-file proposals, not operations or live collaborative edits.
Two clients starting from the same master can both submit; after one is applied,
the other remains pending until manually reconciled. Automatic disjoint merging
is milestone 3. Repeated encrypted saves can produce different byte hashes even
when the model is unchanged.

Local working files must be outside the shared workspace, and each process must
use its own working file. Existing applications opening the original Drive file
are not automatically protected: all writers must migrate to this workflow.
Sharing access grants access to all retained snapshots; use Portfolio
Performance's existing encrypted format before creating a shared workspace if
the data should remain encrypted in Drive history.

Prototype snapshots are limited to 50 MiB. Publication failures retain local
files and immutable submissions; callers must not interpret local Save success
as owner acceptance. Refresh only replaces a clean working copy whose parent is
already a retained revision; a submitted-but-unaccepted proposal is kept for
review instead of being silently discarded. The first version has manual
acceptance and refresh, not unattended aggregation.

## Concrete implementation map

The first slice keeps the protocol in the already exported
`name.abuchen.portfolio.model` package. Its classes and responsibilities are:

| Component | Responsibility |
| --- | --- |
| `SharedPortfolioWorkspace` | Workspace identity, verified snapshots, immutable submissions, owner publication and receipts |
| `SharedPortfolioSession` | Local working file association, parent revision, contributor/owner role and submission chain |
| `SharedPortfolioCoordinator` (later) | One local owner process at a time, pending queue, compatibility checks, acceptance and recovery journal |
| `PortfolioChangeSet` (later) | Model-aware comparison and merge of base, proposal and current master |

Reuse `ClientFactory.load/save/saveAs`; do not change the portfolio file format
to introduce sharing. Treat serialization as opaque in the transport layer.
The first slice applies size limits and verifies checksums before accepting a
submission envelope. A future coordinator must deserialize and validate the
candidate before publication, including encrypted-file password handling and
supported model-version checks.

Desktop integration points:

- `name.abuchen.portfolio.ui/.../editor/ClientInput.java`: normal Save,
  Save As, backups, dirty state and autosave. Local save and shared submission
  have separate success states. Autosave stays local. Save As detaches a shared
  association when it targets a different file, never inheriting it silently.
- `.../editor/ClientInputFactory.java` and `LoadClientThread.java`: restore a
  session at open time and load a verified local working snapshot. Preserve the
  revision associated with the loaded model; do not reread newer sidecar metadata
  at save time and attach it to stale in-memory data.
- `.../editor/PortfolioPart.java`: display role, pending/accepted/conflict status;
  refresh or replace the editor model only when local work is preserved.
- `name.abuchen.portfolio.bootstrap/Application.e4xmi`: File → Shared portfolio
  actions for create, join, refresh and owner review. Implement handlers in
  `name.abuchen.portfolio.ui/.../handlers/` and standard JFace dialogs.

CLI integration is in `name.abuchen.portfolio.cli/.../PortfolioShell.java`.
Implemented commands are `SYNC INIT <folder>`, `SYNC JOIN <workspace>
<new-local-file>`, `SYNC SUBMIT`, `SYNC REFRESH`, `SYNC STATUS`, `SYNC PENDING`
and `SYNC ACCEPT <submission-id>`. `STORE` persists locally; `SYNC SUBMIT`
publishes using the same session service used by desktop.
Errors must distinguish saved locally, submitted, accepted, conflict and delivery
failure. Keep ordinary `OPEN`/`STORE` behavior for unconnected files.

Before changing Save, specify and test a crash-safe publication sequence:

1. Serialize to a temporary local file, then replace the working file while
   retaining its backup. Record the pending submission in a durable local journal.
2. Publish the complete immutable submission package; only then advance its local
   submission-chain metadata. A retry uses the same ID and verifies the payload.
3. The owner validates under a local exclusive coordinator lock, journals the
   acceptance, publishes the immutable revision, then updates the master pointer.
4. Update the owner's working copy and receipt, then complete the journal. On
   restart, finish or report incomplete publication without silently dropping work.

Content hashes alone do not identify an acceptance after the master advances.
Keep acceptance IDs and lineage in the journal/receipts so a replay cannot apply
twice, even if a previous response or receipt was lost. A missing snapshot or
submission dependency means "waiting for sync", not permission to overwrite.

## Suggested small implementation slices

1. Protocol types and fixtures (implemented): filesystem transport tests cover
   two contributors, a single owner, chained/stale submissions and recovery
   from malformed workspaces.
2. Local session persistence and owner coordination (implemented in part):
   sidecars, clean refresh checks and a local owner lock are present; durable
   crash journaling and production-format validation remain.
3. Desktop create/join and explicit submit/refresh/review actions (implemented
   in part), including Save As detachment. Dedicated review/status UI and
   lifecycle tests remain.
4. CLI integration using the same services (implemented), plus broader
   desktop/CLI interoperability tests and owner review/acceptance scenarios.
5. Real two-machine Drive trial using sanitized sample data. Only then design
   semantic merge against the cases discovered during the trial.

Core tests belong in `name.abuchen.portfolio.tests`, CLI command tests in
`name.abuchen.portfolio.cli.tests`, and desktop lifecycle tests in
`name.abuchen.portfolio.ui.tests`. Verify changed modules with Maven/Tycho before
packaging. Host Java 21 is available, but host Maven 3.9.9 failed to initialize
Tycho 5.0.4 during inspection. Use the existing disposable Maven 3.9.11 Docker
build environment; do not replace the running development container merely to
run tests. No additional tools or account connections are needed for planning.

## Decisions to settle while implementing

- Initial owner aggregation is explicitly triggered. Background aggregation
  waits until recovery and conflict reporting are reliable.
- Ownership transfer needs a deliberate handoff with the former owner stopped;
  do not attempt automatic election through an eventually synchronized folder.
- Choose a portable local configuration location and explain how to keep owner
  credentials and working files outside all synchronized folders.
- Refine retention/cleanup only after receipts, referenced bases and history can
  be traced safely. Do not delete proposals just because they are stale.
- A future merge engine must retain the actual common base, not just its hash.
  Keep referenced revisions and submission payloads until descendants are resolved.
- The 50 MiB limit and proposed filenames are starting design choices, not
  compatibility guarantees. Freeze and version the protocol before release.
