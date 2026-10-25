# AGENTS.md

Instructions for AI coding agents working in this repository. Read this before touching code.

## Project

`minecraft-mcp` is a Java MCP (Model Context Protocol) server for Minecraft: Java Edition.
The goal is a drop-in, mod-friendly server that covers as many loaders and versions as
practically possible. The design is **two tiers, one core**:

- `core/` - loader-agnostic protocol, tool registry, policy, audit. **No Minecraft imports.**
- `adapters/fabric/`, `adapters/neoforge/` - deep in-game adapters.
- `adapters/rcon/` - protocol-only fallback (RCON + Query + log tail).
- `stdio-shim/` - stdio-to-HTTP bridge for stdio-only MCP hosts.

Read `docs/architecture.md`, `docs/security.md`, and `docs/version-matrix.md` before changing
module boundaries. If a change contradicts those documents, update the documents in the same PR.

## Hard constraints

1. **PR-only.** Never push to `main`. Branch, commit, open a PR, link the issue.
2. **Small PRs.** One issue per PR where practical. Split refactors from behavior changes.
3. **No Minecraft classes in `core/`.** Adapters depend on core, never the reverse.
4. **No blocking the game main thread.** All world reads/writes marshal to the main thread;
   every tool call has a timeout and cancellation path.
5. **Registry-driven, never vanilla-hardcoded.** Resolve blocks/items/entities by registry id.
   `run_command` must go through Brigadier so modded commands work.
6. **Security defaults are the product.** Loopback bind, generated bearer token (0600, never
   logged), read-only by default, admin/destructive actions behind explicit flags and
   confirmation. Game text is untrusted and can never authorize a command.
7. **No secrets in the repo.** No tokens, server jars, world data, or personal config.
8. **Version/loader matrix is honest.** Do not claim one universal jar. Add a target only when
   its CI smoke test exists.

## Build and test

```bash
./gradlew build          # compile + tests + coverage gate for all modules
./gradlew :core:test     # core tests only
./gradlew :core:pitest   # mutation testing (slow; CI runs it after build)
./gradlew :stdio-shim:pitest   # mutation testing for the shim (CI runs it too)
./gradlew :adapters:rcon:pitest   # mutation testing for the universal tier (CI runs it too)
./gradlew :adapters:fabric:1.21.1:runGameTest   # game tests with fake players on a real server (CI runs it)
scripts/smoke/run-in-docker.sh <fabric|neoforge> <jar>   # real headless-server smoke test (CI runs it; needs Docker)
```

JDK 21 runs Gradle and is required for the core and 1.21.x nodes. JDK 25 is also required, for the 26.x
nodes, so a plain `./gradlew build` needs both installed. Use the Gradle wrapper; do not install a global Gradle.

## Quality gates

These are enforced by the build and CI. Never lower a threshold, add an exclusion, or skip a
check to get green; add a test that pins real behavior instead.

- **Coverage:** `build` fails below 100% JaCoCo line and branch coverage.
- **Mutation:** `:core:pitest` fails below 100% mutation score. A surviving mutant means a
  behavior no test pins down. Fix the test, or simplify the code if the mutant is truly
  equivalent (for example a redundant call).
- **Reports:** `core/build/reports/jacoco` and `core/build/reports/pitest`.
- **Assert behavior, not execution.** Check exact values, messages and boundaries (min, max,
  min-1, max+1). A test that only calls code satisfies coverage and fails mutation testing.
- **Platform-dependent code** (file modes, line endings, paths) behaves differently on Windows
  and Linux, and CI runs Linux. Guard POSIX-only assertions with `Assumptions`, and verify on
  Linux (WSL or Docker) before claiming a green result from Windows.
- **Adapters:** loader glue (`adapters/*`) cannot be unit tested without a game, so the coverage
  gate is off there. Keep those classes tiny, put logic in `core` where the gate applies, and prove
  the adapter with a headless-server smoke test.
- **Smoke tests:** unit tests do not prove a mod loads; see the headless-server rule below.

## Dependencies

- Dependency verification is on. After adding or bumping anything, regenerate
  `gradle/verification-metadata.xml` with `./gradlew build :core:pitest --write-verification-metadata sha256`
  and review the diff; commit it with the change.
- Adding a dependency needs a reason in the PR. Prefer what the MCP SDK already provides
  (for example its `McpJsonMapper`) over a new library.
- Pin exact versions. Do not use dynamic versions or `latest`.

## Conventions

- **Tools:** names and argument names are `snake_case`. Every input schema has
  `additionalProperties: false` and an explicit `required` list. Annotations derive from the
  policy scope (`OBSERVE` read-only, `ADMIN` destructive); do not hand-set them.
- **Adding a tool:** follow "How to add a tool" in `docs/tools.md`. Each tool declares a
  capability and side, and has its own golden file under `golden/tools/`; regenerate with
  `GOLDEN_UPDATE=true ./gradlew :core:test` and review the diff. A tool change that does not
  touch its golden file is a bug.
- **Ports:** adapters implement small capability ports (`CommandRunner`, `PlayerReader`,
  `WorldReader`, `RuntimeInfo`), not one large interface. A new tool group adds a port.
- **Java:** prefer records and immutability, no nulls in public APIs, `List.copyOf` on inputs,
  validate in constructors and fail with messages that name the field.
- **Bounded by default:** every query has a cap, every call a timeout. No unbounded loops over
  world data, entity lists or logs.

## Engineering principles

Write the simplest code that meets the acceptance criteria, then stop. These apply to production
code and to tests.

- **Keep it simple (KISS, YAGNI).** No speculative options, hooks, generics or layers "for later".
  Prefer straight-line code, early returns and small methods. If a reviewer needs a diagram to follow
  a method, split or simplify it. Delete dead code and unused parameters instead of keeping them.
- **DRY, for real duplication only.** Extract when the same *knowledge* appears a third time (rule
  of three), not when two things merely look alike. Duplicated test setup belongs in a shared helper
  (`FakeGame`, `TestCores`, `McpTestClients`); duplicated production logic belongs in one place with
  a name. Never copy a constant or message: define it once.
- **One reason to change.** Each class does one job and its name says which. Keep methods short
  and at one level of abstraction. Names carry intent; comments explain *why*, not *what*.
- **Patterns only where they remove complexity.** This codebase already uses ports and adapters
  (`GamePort` and its capability ports), a strategy via a small functional interface
  (`ToolInvoker`), a decorator-style gate around execution (`ToolDispatcher`), and factories with
  validating constructors. Reach for a pattern when it replaces a growing `if`/`switch` or isolates
  something that varies; do not add one to look structured. No singletons or hidden global state.
- **Composition and explicit dependencies.** Pass collaborators through the constructor (including
  `Clock`, `AuditLog`, ports) so code is testable without mocks of internals. Prefer composition
  to inheritance and interfaces to concrete types at module boundaries.
- **Make bad states unrepresentable.** Immutable records and enums with validation in the
  constructor beat flags and strings. Fail fast with a message that names the field or value; never
  swallow an exception or return a vague default.
- **Tests read like specs.** Arrange, act, assert; one behavior per test; name the behavior, not the
  method. Use small fakes, not mocking frameworks. Inject time and I/O boundaries, and avoid
  `sleep` as synchronization where an event or latch will do.
- **Match the surrounding code.** Follow its naming, structure and comment density. Make the
  change the issue needs; log other improvements as issues rather than widening the PR. Do the
  small tidy-up that the change touches (rename, delete, extract) in the same PR when it makes the
  change easier to read.

Before opening a PR, read your own diff once and ask: can anything here be deleted, is anything
duplicated, is there a simpler way to say this, and would the next person find it where they expect?

## Security rules for code

- Never log or print a token, and keep secrets out of `toString()`. Add a test when you
  handle one.
- Treat everything from the game (chat, signs, books, logs, entity names) as untrusted data.
  It may be shown to the model but can never authorize a command or change policy.
- Policy and tool-level checks run before anything reaches Brigadier. Do not add a path that
  skips them, and do not widen a default (bind address, write access, admin) without asking.
- New destructive tools need a policy check, confirmation, and an audit entry.
- Report vulnerabilities privately per `SECURITY.md`; do not put exploit details in issues.

## Stop and ask first

Do not decide these on your own; raise them in the issue or PR and wait:

- the v1 tool list, the auth model, or the remote-access policy;
- a new dependency, module boundary change, or public tool name/schema change;
- relaxing any security default;
- CI, release or secrets changes;
- adding a loader or Minecraft version target (its smoke test must exist first);
- anything hard to reverse (history rewrites, deleting branches, closing issues).

## Workflow

1. Find or create an issue. If the work is not represented, create a small issue first.
2. Branch from `main`: `feat/<issue>-<slug>`, `fix/<issue>-<slug>`, `docs/<slug>`, `chore/<slug>`.
3. Implement the smallest change that satisfies the acceptance criteria.
4. Add or update tests. Behavior changes without tests are incomplete.
5. Run `./gradlew build` and `./gradlew :core:pitest`.
6. Open a PR with `Closes #<issue>`, `Part of #<epic>`, a short summary, and the verification
   commands you ran with their real results. State plainly what was not verified.
7. Read CI after pushing and fix failures. Do not merge your own PR unless the owner
   explicitly asks.
8. Keep the Part of #<epic> checklist in sync when you finish a child issue.
9. After a PR is merged, delete its branch: the remote one (GitHub can do this itself with the repository
   setting "Automatically delete head branches"; if that is off, run `git push origin --delete <branch>`)
   and the local one (`git branch -d <branch>`; a squash-merged branch needs `-D`, after checking the PR
   merged). Never delete a branch with an open PR or unmerged work, and never touch `main`.

Commit messages: short imperative subject, then a body explaining why when it is not obvious.
Do not commit build output, IDE files, local config, or `.claude/` worktrees.

## Issue conventions

- Epics carry the `epic` label and a child checklist.
- Tasks carry one `area:*` label and one `priority:*` label.
- Every task body has Context / Deliverable / Acceptance criteria / Dependencies / References.
- Reference the parent epic as `Part of #<epic>`.
- Prefer many small issues over a few large ones. A task should be reviewable as one PR.

## Architecture gotchas

- **Mixins are a last resort.** Prefer loader APIs. Any mixin must be version-gated and tiny;
  mixins are the top cross-version maintenance cost.
- **Shade and relocate** Jackson, servlet containers, and logging libraries. Minecraft ships
  Gson and Netty; collisions are real.
- **26.x is unobfuscated** (Mojang names). 1.21.x still needs Loom remapping. Version differences go in
  `GameCompat` (`adapters/common/src/compat/{legacy,modern}`), not scattered through the shared code.
- **Fabric and NeoForge metadata differ.** Keep loader metadata in per-loader source sets.
- **Paper/Spigot is not a mod loader.** A plugin goes in `plugins/`, not `mods/`. Use the
  universal RCON tier or a separate plugin adapter.
- **Test with a headless server.** Unit tests do not prove a mod loads. Every supported target
  needs a Docker smoke test that starts the server and calls `initialize` + `tools/list` + one
  read + one write.

## Where things live

| Need | Read |
|---|---|
| Module boundaries, threading | `docs/architecture.md` |
| Threat model, defaults | `docs/security.md` |
| Config keys and token handling | `docs/configuration.md` |
| Tool catalog and conventions | `docs/tools.md` |
| stdio shim for stdio-only hosts | `docs/stdio-shim.md` |
| Fabric adapter | `docs/fabric.md` |
| Supported loaders and versions | `docs/version-matrix.md` |
| Milestones and order of work | `docs/roadmap.md` (issues are the source of truth) |

## Definition of done

- Acceptance criteria met and demonstrated with real output.
- `./gradlew build` and `./gradlew :core:pitest` green (100% coverage, 100% mutation score).
- Docs updated (README, architecture, security, version matrix) when behavior or configuration
  changes.
- No new secret, no new unbounded query, no new blocking call on the main thread.
