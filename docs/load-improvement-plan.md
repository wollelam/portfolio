# Background price loading: analysis and implementation plan

## Goal and baseline

Make quote updates stop monopolizing the SWT UI thread while preserving progress reporting, dirty-file handling, feed grouping/rate limits, and the final recalculation of the active view.

This plan was prepared on branch `load-improvement`, based on `upstream/master` commit `3c394a7324070af4e4b0c7af9c5d2bad5b23c89a` (`Bump com.puppycrawl.tools:checkstyle in /portfolio-app`). It intentionally does not include the later local chart-currency commits.

## What happens today

The downloads already run in Eclipse background jobs:

1. `UpdateQuotesHandler` schedules `UpdatePricesJob`.
2. `UpdatePricesJob` creates one `RunTaskGroupJob` per feed grouping criterion.
3. Each task downloads a historical or latest quote and immediately mutates the live `Security`.
4. A private scheduler wakes every 300 ms. It publishes progress and, if any task changed a security, calls `Client.markDirty()`.
5. `ClientInput` forwards that dirty event to the SWT display thread.
6. `PortfolioPart.onRecalculationNeeded()` calls the active view's `onRecalculationNeeded()`.
7. The active view rebuilds snapshots/models and refreshes SWT viewers on the UI thread.

The network is therefore not the direct cause of the freeze. The repeated recalculation of the active view is.

The March 2026 fix for [PR #5557](https://github.com/portfolio-performance/portfolio/pull/5557) coalesces dirty events so an unbounded SWT queue can no longer accumulate. The measurements in that PR still show each recalculation taking roughly 0.4 to 0.9 seconds and about eleven recalculations during a roughly 20-second quote update. Coalescing removes the backlog after the update, but the UI remains sluggish during it. [PR #5406](https://github.com/portfolio-performance/portfolio/pull/5406) also records the intended UX: live progress is useful, but repeatedly rebuilding the views is not.

There is also a concurrency concern: `Task` changes `Security.prices` and `Security.latest` from background job threads while UI calculations can read the same live model. The model lists are ordinary `ArrayList` instances and have no general read/write lock. The proposed first change does not make this worse, but it should be addressed by the follow-up design below.

## Dataset observations

Only aggregate metadata was read from the two supplied files; no portfolio contents or names were copied into the repository.

| File | Securities | Historical prices | Transactions | Uncompressed data |
| --- | ---: | ---: | ---: | ---: |
| PortOle.portfolio | 206 | 626,823 | 5,029 | about 9.0 MB |
| PortYing.portfolio | 22 | 67,993 | 1,703 | about 0.8 MB |

The larger file explains why a complete performance/snapshot rebuild can exceed the 300 ms update interval. Increasing the interval is machine- and view-dependent and cannot guarantee responsiveness.

## Recommended implementation: separate progress, persistence, and recalculation

Implement this small change first. It targets the demonstrated bottleneck and is suitable for a lower-cost coding agent.

The implementation exposes this behavior as a global application setting under
**Settings → General → Prices → View refresh mode**. `LIVE` preserves the
existing periodic view refreshes and is the default;
`BATCHED` keeps progress live but defers view recalculation until the update ends.

### Required behavior

- Continue publishing `PriceUpdateSnapshot` every 300 ms. The progress bar and `SecurityPriceUpdateView` remain live.
- When quote data changes during the update, mark the client as changed via `Client.touch()`. This updates the dirty state but deliberately does not request view recalculation.
- Remember, for the lifetime of the request, whether any task changed quote data.
- When all task-group jobs have ended, call `Client.markDirty()` exactly once if the request changed anything. This performs one final active-view recalculation.
- If nothing changed, do not recalculate.
- Shut down the per-update scheduler in all completion paths.
- Publish the final snapshot through `PriceUpdateProgress.notifyFinished(...)`, not `notifyProgress(...)`, so `latestJobs` is cleaned up.

### File-by-file changes

#### `name.abuchen.portfolio.ui/.../jobs/priceupdate/PriceUpdateRequest.java`

Replace the single resettable dirty meaning with two explicit states:

- `modified`: sticky for the whole request; once true it remains true.
- `unannouncedModification`: resettable; used only to send a cheap dirty-file notification while work continues.

Suggested API:

```java
private final AtomicBoolean modified = new AtomicBoolean();
private final AtomicBoolean unannouncedModification = new AtomicBoolean();

void markModified()
{
    modified.set(true);
    unannouncedModification.set(true);
}

boolean getAndResetUnannouncedModification()
{
    return unannouncedModification.getAndSet(false);
}

boolean isModified()
{
    return modified.get();
}
```

Rename existing callers from `markDirty()` to `markModified()`. Do not reuse the word `dirty` for both persistence state and recalculation state.

#### `name.abuchen.portfolio.ui/.../jobs/priceupdate/RunTaskGroupJob.java`

After a task returns `UpdateStatus.MODIFIED`, call `request.markModified()` as today, using the renamed method. Do not call `Client.markDirty()` here.

#### `name.abuchen.portfolio.ui/.../jobs/priceupdate/UpdatePricesJob.java`

Keep the periodic callback, but change its responsibilities:

```java
ScheduledFuture<?> periodicUpdate = scheduler.scheduleAtFixedRate(() -> {
    fireSnapshot(request);
    if (request.getAndResetUnannouncedModification())
        request.getClient().touch();
}, 0, UI_PROGRESS_UPDATE_INTERVAL, TimeUnit.MILLISECONDS);
```

In `finally`:

1. Cancel `periodicUpdate`.
2. Call `scheduler.shutdownNow()` (or `shutdown()` after cancellation). The current executor is otherwise retained by its worker thread after every update job.
3. If `request.isModified()`, call `request.getClient().markDirty()` once. Calling `touch()` first is unnecessary because `markDirty()` also sets the dirty UI state.
4. Queue a final UI callback that invokes `PriceUpdateProgress.notifyFinished(this, snapshot)`.

Split the current `fireSnapshot` helper into clearly named `fireProgress` and `fireFinished` helpers. Both callbacks must remain asynchronous on the SWT display thread. Before queuing, guard against a disposed display if that is the local project convention.

Do not change `UI_PROGRESS_UPDATE_INTERVAL`: progress refresh is cheap and independent after this patch.

#### `name.abuchen.portfolio.ui/.../jobs/priceupdate/PriceUpdateProgress.java`

No behavior change should be needed. Its existing `notifyFinished` method already sends the last status and conditionally removes the completed job from `latestJobs`. Add tests around it if practical.

### Important edge cases

- `jobGroup.join(...)` interruption must still enter `finally`, shut down the scheduler, and publish a terminal snapshot.
- A task that downloads data but produces `UNMODIFIED` must not dirty or recalculate the client.
- A mix of modified, unmodified, skipped, and failed tasks results in one final recalculation if at least one task modified data.
- Rate-limit retries must not create extra model recalculations.
- A newer update job for the same client may supersede progress from an older job. Preserve the existing `isCurrent` check. The older job must still shut down its executor and preserve its model changes even if its progress is hidden.
- Do not copy either private portfolio into a test resource or commit it.

## Tests for the first implementation

Add focused tests under `name.abuchen.portfolio.ui.tests/src/name/abuchen/portfolio/ui/jobs/priceupdate/`.

1. **Request state test**
   - Initially `isModified()` and the resettable notification flag are false.
   - `markModified()` makes both true.
   - Resetting the notification flag does not reset `isModified()`.
   - A second modification makes the notification flag true again.

2. **Task tests**
   - Keep `HistoricalTaskTest` and `LatestTaskTest` passing.
   - They already cover modified versus unmodified outcomes and replacement policies.

3. **Job-level notification test**
   - Use a deterministic fake `QuoteFeed` and a client property-change listener.
   - Run an update with several modified securities.
   - Pump the SWT event queue while the job runs.
   - Assert there may be one or more `touch` events during the run, but exactly one `dirty` event/recalculation request is produced by the completed update.
   - Run the same job with identical prices and assert zero `dirty` events.
   - Assert the final progress snapshot completes all tasks and the job is removed from `PriceUpdateProgress.latestJobs` through observable behavior (for example, a stale completion must not suppress a later job).

4. **Lifecycle test**
   - Cancel or interrupt a slow fake feed.
   - Assert the parent job terminates and no periodic callback continues afterward. If direct executor inspection would require exposing implementation details, assert that the progress listener receives no further callbacks after completion plus a short safety window.

Run the focused UI test command from `AGENTS.md`, including `:name.abuchen.portfolio.bootstrap`. For example, after choosing the final test class name:

```shell
mvn -f portfolio-app/pom.xml verify -Plocal-dev -o \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.bootstrap,:name.abuchen.portfolio.ui,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.ui.tests \
  -am -amd -Dtest=name.abuchen.portfolio.ui.jobs.priceupdate.UpdatePricesJobTest
```

Then run all UI tests for the affected bundle if time permits.

## Manual performance validation

Use copies in a temporary directory, never the Google Drive originals.

Test at least these active views because their `notifyModelUpdated()` paths build nontrivial snapshots: Securities Performance, Performance, Statement of Assets, and Dashboard.

For each supplied portfolio:

1. Open the copied file and wait for initial jobs to settle.
2. Trigger “Update Quotes” for all instruments.
3. While it runs, repeatedly switch views, open a menu, scroll a table, and edit a harmless cell without saving.
4. Record update duration, number and duration of `ClientInput.setDirty(..., recalculate=true)` calls, maximum SWT dispatch gap, and process CPU.
5. Repeat three times before and after the patch with the same active view and quote state.

Acceptance criteria:

- Progress continues to update during the download.
- No repeated full-view recalculations occur while the update is running.
- At most one recalculation occurs at completion when quotes changed; none occurs when no quote changed.
- UI actions remain usable during network work. The one final refresh should not create a multi-second freeze on either supplied portfolio.
- The file becomes dirty after modified quotes and can be saved normally.
- Closing a client or cancelling an update leaves no continuing progress callback or scheduler thread.

If the final single refresh is still too slow, capture a Java Flight Recorder profile for that one refresh before starting the follow-up. The expensive active view, not `UpdatePricesJob`, then determines the next optimization.

## Follow-up: stage downloads before touching the live model

This is the design needed for strict model thread isolation, but it is deliberately not part of the first patch.

Refactor each task into two phases:

1. **Fetch in background:** call the feed with a deep copy of the security and return an immutable result containing downloaded data, errors, update policy/source identity, and the target security UUID. Do not mutate the live `Client` or `Security`.
2. **Apply once:** after all fetch jobs complete, apply successful results to live securities in one SWT-thread transaction, then call `Client.markDirty()` once.

Before applying, verify that the target security still exists and its relevant feed configuration has not changed since the request was created. Define behavior for manual price edits made while the fetch was in flight. Add cancellation propagation from `UpdatePricesJob` to child `RunTaskGroupJob`s.

This removes concurrent reads/writes of `Security.prices`, gives the UI a stable old snapshot throughout downloading, and makes the new prices visible atomically. It is a larger change because terminal `MODIFIED`/`UNMODIFIED` status cannot be known until apply time and because feed configuration conflicts need an explicit policy.

## Out of scope for the first patch

- Moving every view's snapshot calculation off the SWT thread.
- Adding locks throughout the domain model.
- Changing feed concurrency or rate-limit grouping.
- Optimizing `Security.addAllPrices`; normal incremental downloads are small, and this is not the demonstrated UI-thread bottleneck.
- Persisting or sanitizing the supplied private portfolios as fixtures.
