---
title: Implementation workflow
description: Execute MeridianDB's Java and Maven plan one bounded queue step at a time, with expandable work, verification, documentation, commits and pushes.
---

# Implementation workflow

This is the execution procedure for MeridianDB's Java/Maven implementation queue.
The owner established it on October 28, 2026. The first target is an embedded,
single-node bitemporal engine, progressing from explicit semantics to a durable,
measured prototype. The [roadmap](roadmap.md) describes that scope.

## Start here

| Document | Authority |
| --- | --- |
| [Live queue](queue.md) | Current order, state, dependencies, parent/child relationships and checkpoint |
| [Implementation roadmap](roadmap.md) | Technical outcomes and completion criteria for each initial item |
| [Progress history](progress.md) | Dated evidence of work performed; historical recommendations are not the live cursor |
| `AGENTS.md` at repository root | Repository-wide engineering and authorization rules |

Use repository files as the handoff between sessions. GitHub Issues or Projects
may later mirror this queue; they do not currently own its state. There is no
scheduler or background execution process. An agent follows this procedure when
invoked by the owner.

## Invocation and scope

A request such as **Start the implementation queue**, **Continue the implementation
queue**, or **Continue** while working this queue authorizes one bounded executable
step, including its implementation, focused checks, related documentation,
planning updates, commit and push to `origin/main`. Resume an unfinished step
before selecting another. A request to prepare or revise the plan alone performs
that planning work and leaves the next implementation step ready.

The owner's standing source-push authorization applies to this queue and its
planning setup. Documentation changes pushed to `main` also invoke the configured
GitHub Pages publication workflow under the owner's October 28, 2026 automatic
publication instruction. Follow the
[website guide](../contributing/documentation.md#initial-hosting-and-publication)
and report its actual deployment outcome. Release tags, Maven Central uploads,
GitHub Releases, manual Pages dispatches, Cloudflare deployments and purchases
remain separately scoped actions.

## Execute one iteration

1. **Reconcile the checkout.** Read root `AGENTS.md`, this procedure, the live queue,
   the selected roadmap card and its relevant decisions. Inspect branch, working
   tree and index; fetch `origin`. Start from up-to-date `main` when clean, using a
   fast-forward when needed. Preserve unrelated edits and staged work. If another
   writer owns the checkout or queue, coordinate ownership before editing it.
2. **Select and claim.** Resume the single `active` item. Otherwise select the first
   eligible `ready` item in table order. Promote a `queued` item whose prerequisites
   are complete if needed. Verify the preceding completion commit reached the
   remote before advancing. Mark the chosen item `active`, record its owner and
   checkpoint in the queue, and state its bounded outcome.
3. **Refine before building.** Read the acceptance criteria and identify affected
   contracts, files and checks. If the item covers several independently useful
   changes, expand it using the procedure below, then execute one eligible child.
   Record any new decision or prerequisite discovered during the work.
4. **Implement the selected outcome.** Use Java and Maven for the engine. Keep
   behavior explicit, test expected results independently and preserve failing
   reproductions. Resolve concrete failures within this step. Introduce only the
   abstraction or dependency that the current outcome requires.
5. **Verify and review.** Run the focused behavioral checks and the applicable
   Maven verification once the wrapper exists. Run the strict documentation build
   for changed public docs. Inspect the diff for correctness, unrelated changes,
   command accuracy and proposed-versus-implemented claims. Address failures
   before calling the source complete.
6. **Update the handoff in the same change.** Update the relevant project docs,
   decisions and examples; update README setup/status when affected. Always update
   the queue and append a progress receipt with actual changes, checks/results,
   remaining limits and discovered tasks. Mark the source outcome `done` only when
   its acceptance criteria pass. Clear its active claim and mark the next
   source-ready item `ready`; execution still waits for remote delivery and CI
   checks. Planning a later item does not start it.
7. **Commit and push.** Inspect the working tree and staged diff; stage explicit
   task-owned paths. Include implementation, documentation, queue and receipt in
   the completion commit. Use the trailers below. Push `main` to `origin` with a
   normal push, verify the completion is reachable from remote `main`, and inspect
   relevant CI and any automatically triggered documentation deployment. Fix
   failures attributable to the step and push the repair before handing it off as
   complete. If CI or deployment is still pending, report that exact state; the next
   invocation checks its outcome before starting new work.
8. **Stop and recommend.** Report the outcome, checks, commit, actual push/CI state,
   publication dependencies and one next task by ID and title. Recommend the next
   eligible queue item, or the concrete unblock/retry action if delivery is pending.
   A recommendation does not execute the next item.

One coordinator owns queue state and Git operations. Helpers, when appropriate,
receive bounded file or review ownership and report evidence to that coordinator.
Concurrent agents must not independently advance the same checkout's queue.

## Expand, insert and revise work

Keep stable IDs in the form `MDB-0001`. Allocate the next unused numeric ID for
new work, including children; preserve completed, cancelled and expanded rows so
IDs are never recycled. Position in the queue determines order, not the number.

When splitting a task:

1. Retain the parent's ID, original outcome and acceptance criteria. Change its
   state to `expanded`; it becomes a milestone, not an executable item.
2. Add child rows with new IDs, their parent ID and explicit dependencies. Each
   child inherits the parent's external prerequisites. Children depend on earlier
   siblings only when their outputs are actually required. They do not depend on
   their own parent, which would create a cycle.
3. Add or update the corresponding roadmap cards with concrete outputs, checks and
   completion criteria. Insert the children near the parent in the intended order.
   Execute only the first eligible child in this iteration.
4. Mark the parent `done` when every required child and the parent's integration
   criteria pass. Close it in the last child's completion commit when practical.
   Existing downstream dependencies continue to refer to the parent. If integration
   remains, add an explicit integration child and keep the parent `expanded`.

All admitted children are required for their parent. Keep optional ideas deferred
outside that parent until their scope is deliberately admitted.

For a newly discovered prerequisite, insert a new item before the consumer, add
its dependency link and record the reason. Defer an enhancement that does not
block the selected outcome. Keep the acceptance criteria intact when decomposing
work; splitting a task does not satisfy it. Reordering and refinement within the
agreed prototype scope are normal agent work. A material change to product scope,
durability guarantees or external spending needs an owner decision.

Before committing a planning change, check unique IDs, existing dependency and
parent references, absence of cycles, at most one executable active item, and that
completed parents still satisfy their children. Keep one authoritative state in
the queue; the roadmap describes outcomes and the progress log preserves history.

## Completion and Git evidence

Use a descriptive subject and trailers on completion commits, for example:

```text
MDB-0002: Define the initial temporal contract

Step-ID: MDB-0002
Step-Result: completed
```

A `done` queue row means the source acceptance criteria are met in its completion
commit. Delivery is complete only when that commit is on remote `main`. Git is the
authority for commit and push evidence: do not try to put a commit's own SHA into
the files it contains. Find the receipt's completion commit by its trailers; later
receipts can cite earlier SHAs when useful.

Confirm remote ancestry after a push by fetching `origin` and checking that the
completion SHA is an ancestor of `origin/main`. Also confirm local and remote tips
match, or explicitly explain any later remote commits. This avoids repeating
implementation simply because a prior push report or progress note is stale.

If a push fails, retain the validated source commit and retry delivery first.
Reconcile a non-fast-forward with the new remote history while preserving other
work, resolve conflicts and rerun affected checks. Use normal pushes; preserve
published history. Branch protection, unavailable credentials or unresolved
conflicting work are concrete handoff dependencies, not reasons to bypass rules.

If work cannot be completed in this invocation, preserve a reviewable checkpoint
with `Step-Result: checkpoint`, the unfinished acceptance criteria and an `active`
or `blocked` queue row. Record a blocker, evidence, unblock condition and next
action. Never report that step complete or start its dependent while work remains.

## Verification commands

Current documentation command, from the repository root:

```bash
.venv/bin/zensical build --strict
```

Current Java verification command, using the tracked Maven Wrapper and JDK 25:

```bash
./mvnw -B verify
```

This runs temporal type/boundary checks, canonical histories against both reference
and embedded engine, seeded dense-state comparisons and focused engine
lifecycle/concurrency checks. It does not establish durability or crash recovery.
Follow the [reference guide](../development/reference-model.md) and
[engine guide](../development/embedded-engine.md) for coverage, reproduction and
example execution, and the [build guide](../development/build.md)
for the owner's mandatory current-artifact research before each Maven change.
Later cards must name their relevant unit, integration, crash/recovery or benchmark
checks. Keep benchmark claims tied to measured semantics, durability and workloads.

## Reusable start prompt

```text
Read AGENTS.md and docs/planning/index.md in this repository. Continue the
implementation queue for one bounded step. Reconcile Git and the live queue,
resume unfinished work or select the first eligible item, and split it if needed.
Implement and verify the outcome, update project documentation, queue and progress,
commit task-owned changes and push to origin/main. Report actual delivery state
and recommend the next queue item. Check automatically triggered GitHub Pages
publication; leave library releases and Cloudflare deployments separately scoped.
```
