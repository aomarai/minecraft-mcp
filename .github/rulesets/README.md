# Repository rulesets

These are importable GitHub repository rulesets. They are version-controlled here so changes are
reviewable; applying them still requires repository admin access.

## Files

- `branch-protection.json` — protects `main`: no deletion, no force-push, PR + one approving
  review (code owner review required, stale reviews dismissed, threads resolved), and the
  `build-and-test` check must pass on an up-to-date branch.
- `tag-protection.json` — protects release tags matching `refs/tags/v*` from deletion and
  force-moves.

## Apply

UI: **Settings → Rules → Rulesets → New ruleset → Import a ruleset**, then select the JSON file.

API (admin token required):

```bash
gh api --method POST /repos/aomarai/minecraft-mcp/rulesets \
  --input .github/rulesets/branch-protection.json

gh api --method POST /repos/aomarai/minecraft-mcp/rulesets \
  --input .github/rulesets/tag-protection.json
```

## Notes

- Merge the scaffold PR first. It adds the `build-and-test` workflow and `.github/CODEOWNERS`,
  which the branch ruleset depends on.
- The `build-and-test` context must match the workflow job name. If the job is renamed, update
  the ruleset.
- Repository admins are a bypass actor with `bypass_mode: always` for emergency access. Remove
  the `bypass_actors` block if you want no bypass at all.
- The branch ruleset requires one approving review plus a code-owner review. With two
  collaborators, each PR needs the other identity to approve; the author cannot self-approve.
- Sourcery is not explicitly required by this ruleset. If "every PR needs a Sourcery review" is a
  hard rule, add a required status check from Sourcery or a workflow gate.
- `strict_required_status_checks_policy` requires branches to be up to date before merging. Set it
  to `false` during early scaffolding if rebase churn is not worth it.
