# Contributing

## Workflow

- **PR-only.** Do not push directly to `main`.
- One issue per PR where practical. Link the issue with `Closes #<n>`.
- Keep PRs small enough to review in one sitting. Split refactors from behavior changes.
- Every PR must pass CI (`./gradlew build`) and include tests for behavior changes.
- Do not commit secrets, tokens, server jars, or world data.
- Releases follow the [release checklist](docs/release-checklist.md).

## Commit messages

Use a short imperative subject and explain *why* in the body when the change is not obvious.

## Code style

- Java 21 for the core; Java 25 only in 26.x adapter nodes.
- No Minecraft classes in `core`.
- No blocking calls on the game main thread.
- Prefer records for immutable data and interfaces for ports.

## Security

- Never log bearer tokens or full command output that may contain secrets.
- New destructive tools need a policy check and an audit entry.
- Report vulnerabilities privately per [SECURITY.md](SECURITY.md).
