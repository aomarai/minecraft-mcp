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
- Gradle's own start cost is seconds per invocation, so sharing a daemon across jobs is not worth it. Configuring the
  Fabric and NeoForge nodes is not cheap, though; see below.
- The Fabric and NeoForge builds prepare Minecraft for each version node; the NeoForge nodes do it one at a time
  (the `neoformRuntime` build service in `build.gradle.kts`) so the runner does not run out of memory.
- Package downloads on the runner can stall. Twice a `smoke-client` job took 9 to 14 minutes instead of 3 because
  `apt-get` waited on one slow mirror download (`mesa-vulkan-drivers`, which trickles in past any inactivity
  timeout). Those jobs now use archive.ubuntu.com instead of the runner's default Azure mirror, only the 26.3 job installs the
  driver, and the install is bounded to 2 minutes per attempt with three attempts.

## Configuring only what a job builds

A Gradle build configures every project in the build, and configuring a Fabric or NeoForge node makes Loom download
and prepare Minecraft. The `core`, `mutation` and `universal` jobs need none of that, so they pass
`--configure-on-demand`: in the log of a `:core:pitest` job, configuring the six nodes took 110 of its 412 seconds
(20:32:13 to 20:34:02). The jars, the 100% coverage gate and the mutation scores are the same with and without the
flag (checked locally). The Fabric and NeoForge jobs build the nodes, so they do not use it.

## Caching dependencies (#88)

Not done, on purpose. Dependency caching is off so that every run verifies freshly downloaded artifacts against
`gradle/verification-metadata.xml`. After the changes above the cold downloads that remain are the Minecraft
artifacts of the nodes in the `fabric`, `neoforge` and `smoke-client` jobs (160 to 200 s each, in parallel and not on
the longest path), so a cache would save about a minute on jobs that are not the slowest, at the price of a supply
chain check. Revisit it if those jobs become the longest.

## Gradle memory

`gradle.properties` gives the Gradle JVM `-Xmx2g -XX:MaxMetaspaceSize=512m`. Measured with `jstat -gc` every 2 s on a
full rebuild (`./gradlew :core:build :stdio-shim:build :adapters:rcon:build :adapters:fabric:1.21.1:build
:adapters:fabric:26.3:build --rerun-tasks`, 4 CPUs): peak heap used 0.95 GB and peak metaspace used 0.2 GB. The caps
are about twice and two and a half times that. Test, Pitest and game JVMs are separate processes with their own
settings, and preparing Minecraft for a NeoForge node runs in a JVM of its own (up to 4 GB; the NeoForge nodes
take turns, see `build.gradle.kts`), so none of those is covered by these numbers. Before this the metaspace had
no cap; its exhaustion in `:adapters:fabric:shadowJar` is what broke the SonarQube job (#80), which is why that job
keeps its own limits.
