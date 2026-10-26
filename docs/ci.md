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
- Package downloads on the runner can stall. One run of `smoke-client (26.3)` took 14 minutes instead of 3 because
  `apt-get` waited 8 minutes on one mirror download; the install step now times out after 30 seconds and retries.

## Gradle memory

`gradle.properties` gives the Gradle JVM `-Xmx2g -XX:MaxMetaspaceSize=512m`. Measured with `jstat -gc` every 2 s on a
full rebuild (`./gradlew :core:build :stdio-shim:build :adapters:rcon:build :adapters:fabric:1.21.1:build
:adapters:fabric:26.3:build --rerun-tasks`, 4 CPUs): peak heap used 0.95 GB and peak metaspace used 0.2 GB. The caps
are about twice and two and a half times that. Test, Pitest and game JVMs are separate processes with their own
settings, and preparing Minecraft for a NeoForge node runs in a JVM of its own (up to 4 GB; the NeoForge nodes
take turns, see `build.gradle.kts`), so none of those is covered by these numbers. Before this the metaspace had
no cap; its exhaustion in `:adapters:fabric:shadowJar` is what broke the SonarQube job (#80), which is why that job
keeps its own limits.
