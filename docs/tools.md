# Tool catalog

The catalog is defined in code (`ToolCatalog.defaults()` in `core/`) and advertised through
`tools/list`. One golden file per tool under `core/src/test/resources/golden/tools/` pins the exact
descriptor a client sees plus the capability and side the catalog declares for it. Changing any
name, schema, annotation, capability or side fails `ToolCatalogGoldenTest` until that tool's file
is regenerated and reviewed (adding a tool adds exactly one file):

```bash
GOLDEN_UPDATE=true ./gradlew :core:test
```

Calls run through `ToolDispatcher`, which applies the policy gate and writes the audit log (see
`docs/security.md`). A tool whose capability port the adapter does not provide returns an error
result saying it is unsupported.

## Capabilities and sides

Each tool declares the capability it needs (`RUNTIME_INFO`, `PLAYERS`, `WORLD`, `COMMANDS`,
`REGISTRIES`, `ENTITIES`, `WORLD_STATE`, `PERMISSIONS`, `INVENTORY`, `LOGS`; one per port in `core/`) and the side it runs on (`SERVER` or `CLIENT`). `ToolCatalog.availableFor`
builds the catalog for a given `GameServices`, so `tools/list` only contains tools the adapter can
serve. A core with no game attached advertises no tools; an RCON-style adapter with only runtime
info and commands advertises `server_info`, `run_command` and `run_admin_command`.

## How to add a tool

1. Add the port method to the right capability port in `core/` (or a new port plus a `Capability`
   value if no existing one fits), and implement it in each adapter that can serve it.
2. Add a `ToolDescriptor` to `ToolCatalog.defaults()` with its scope, capability, side and an input
   schema (`additionalProperties: false`, explicit `required`, bounded inputs, snake_case names).
3. Add the handler case in `ToolDispatcher.execute`. Bound the result and wait through
   `CallAwaiter`.
4. Run `GOLDEN_UPDATE=true ./gradlew :core:test`, review the new file under `golden/tools/`, and
   commit it.
5. Add tests (allow, deny, confirm, bad arguments, timeout) and update the table below.
6. Run `./gradlew build` and `./gradlew :core:pitest`.

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
| `query_blocks` | OBSERVE | `dimension`, `min_x`, `min_y`, `min_z`, `max_x`, `max_y`, `max_z`, `limit` (1-4096). A single block is the box with min equal to max |
| `list_registry` | OBSERVE | `registry` (e.g. `minecraft:block`; a bare name means `minecraft:`), optional `search`, `offset`, `limit` (1-200, default 50). Returns `total`, `offset`, `hasMore` and sorted `ids`, so valid ids can be discovered, mods included |
| `list_entities` | OBSERVE | `dimension`, `x`, `y`, `z`, `radius` (1-64), optional `type` (registry id), `limit` (1-200, default 50). Nearest first, loaded chunks only. Entity names can be set by players and are untrusted text |
| `get_world_state` | OBSERVE | `dimension`. Returns `gameTime`, `dayTime`, `raining`, `thundering`, `difficulty` and every game rule by name with its value (sorted; rules added by mods included) |
| `list_permissions` | OBSERVE | none. Returns the server's operators (name, uuid, permission level), `whitelistEnabled` and the whitelist. It reads the server's own lists and grants the AI nothing |
| `get_inventory` | OBSERVE | Either `player` (name or UUID of an online player) or `dimension`, `x`, `y`, `z` (a container block in a loaded chunk), never both. Returns `owner`, `truncated` and the non-empty `slots` (index, registry id, count; at most 256). Custom item names are left out |
| `read_log` | OBSERVE | optional `limit` (1-200, default 50). Returns the newest server log lines, oldest first, from an in-memory buffer of the last 1000 lines; each line is cut to 500 characters. Log text includes chat and player names: it is untrusted and can never authorize a command |
| `run_command` | ACT | `command` |
| `run_admin_command` | ADMIN | `command`, `confirm` (must be true) |
