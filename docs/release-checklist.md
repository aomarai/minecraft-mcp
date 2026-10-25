# Release checklist

Work through this before pushing a release tag ([release.md](release.md) explains the pipeline). The items
marked **automated** are checked by CI and fail the build or the release; the rest are yours.

## Legal

- **Mojang and Minecraft code is not redistributed.** Jars contain only this project's classes; the game is a
  compile-time dependency fetched by the build. *Automated:* `scripts/release/check-jars.sh` fails if any jar
  holds `net/minecraft/` or `com/mojang/` classes.
- **No server jar, game jar or world data is committed.** *Automated:* `scripts/release/check-repo.sh` (runs on
  every build and again at release) allows only the Gradle wrapper jar and rejects world files, `eula.txt` and
  `server.properties`.
- **The project is MIT licensed.** *Automated:* the same script checks `LICENSE` is the MIT text.
- **The Minecraft EULA is the operator's to accept.** The docs and smoke tests tell people to accept it
  themselves on their own server; nothing here accepts it for them outside the throwaway CI containers. The
  project is unofficial and not affiliated with Mojang or Microsoft: keep that wording where it is.
- Third-party licenses of shaded libraries (Jackson, Jetty, MCP SDK) are unchanged or still compatible.

## Version and artifacts

- The tag is `v<major>.<minor>.<patch>` (optionally `-rcN`). Try the pipeline first with a `-dryrun` tag.
- **Version metadata matches the tag.** *Automated:* `check-jars.sh` reads `fabric.mod.json` and
  `neoforge.mods.toml` from each jar.
- **One jar per node, none extra.** *Automated:* the `package` job compares against `adapters/*/versions/*`.
- **Checksums.** *Automated:* `SHA256SUMS` is generated and attached.
- Every jar passed its Docker smoke test. *Automated:* the release builds on the full `build-and-test`
  pipeline.

## Documentation and notes

- [version-matrix.md](version-matrix.md) matches what CI really tests, and its **Known limitations** are current.
  *Automated:* the release notes append the Status, Targets and Known limitations sections verbatim, next to the
  generated list of changes.
- README, [install.md](install.md), [configuration.md](configuration.md) and [security.md](security.md) describe
  this version's behavior and defaults.
- Security defaults are unchanged (loopback, token, read-only), or the change was approved and is called out.

## After publishing

- Download one jar and run `sha256sum -c SHA256SUMS`.
- Close the milestone's issues and update the epic checklist.
