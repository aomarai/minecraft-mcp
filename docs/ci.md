# CI timings and budget

The `build-and-test` workflow (`.github/workflows/build.yml`) runs its jobs in parallel; the pipeline takes as long
as its slowest chain of jobs. Every run page ends with a table of the job durations
(`scripts/ci/duration-summary.py`) and a warning when the run is over its budget, so a slowdown is seen without
reading logs. The required status check is `build-and-test`, which waits for every other job.

## Baselines

Measured on GitHub's `ubuntu-latest` runners (4 CPUs).

| Date | Change | Wall time | Slowest job |
|---|---|---|---|
| 2026-10-26 | Before: Pitest for all three modules ran one after another inside the `core` job | 12.6 min | `core` 12.4 min (build 3.0 + Pitest 9.2: core 5.1, rcon 3.8, stdio-shim 0.9) |
| 2026-10-26 | Each module's mutation tests in their own job (#143) | 7.4 min | `mutation (:core)` 7.3 min |

The budget in `scripts/ci/duration-summary.py` is 11 minutes: the green pipeline is about 7.5, and the rest is room
for slow runners. Raise it only with a new baseline here.

## What the time goes to

- Mutation testing is the slowest work and uses every CPU, so a module gets a runner of its own.
- Gradle's own start and configuration cost seconds per invocation. It is not worth sharing a daemon across jobs.
- The Fabric and NeoForge builds prepare Minecraft for each version node; the NeoForge nodes do it one at a time
  (the `neoformRuntime` build service in `build.gradle.kts`) so the runner does not run out of memory.
