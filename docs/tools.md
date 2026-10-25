# Tool catalog

The catalog is defined in code (`ToolCatalog.defaults()` in `core/`) and advertised through
`tools/list`. The golden file `core/src/test/resources/golden/tools-list.json` pins the exact
output; changing any name, schema or annotation fails `ToolCatalogGoldenTest` until the file is
regenerated and reviewed:

```bash
GOLDEN_UPDATE=true ./gradlew :core:test
```

Call handlers and the policy gate are not wired yet; every tool currently returns a
"not implemented" error result.

## Conventions

- Tool names and argument names are `snake_case` (`[a-z][a-z0-9_]*`), enforced by `ToolDescriptor`.
- Every input schema is `type: object` with `additionalProperties: false` and an explicit
  `required` list.
- Blocks, items and dimensions are identified by registry id, never display names.
- Annotations are derived from the policy scope, so they cannot drift:

| Scope | `readOnlyHint` | `destructiveHint` | `idempotentHint` |
|---|---|---|---|
| `OBSERVE` | true | false | true |
| `ACT` | false | false | false |
| `ADMIN` | false | true | false |

`openWorldHint` is always false. Annotations are hints for MCP hosts (for example to auto-approve
read-only tools); they are not enforcement. The policy gate decides what actually runs.

## Tools (v1)

| Tool | Scope | Arguments |
|---|---|---|
| `server_info` | OBSERVE | none |
| `list_players` | OBSERVE | none |
| `query_blocks` | OBSERVE | `dimension`, `min_x`, `min_y`, `min_z`, `max_x`, `max_y`, `max_z`, `limit` (1-4096) |
| `run_command` | ACT | `command` |
| `run_admin_command` | ADMIN | `command`, `confirm` (must be true) |
