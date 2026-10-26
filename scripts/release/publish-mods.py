#!/usr/bin/env python3
"""Uploads the per-loader jars of a release to Modrinth and CurseForge.

    publish-mods.py DIST_DIR VERSION NOTES_FILE [--dry-run]

Only the Fabric and NeoForge jars go there; the universal and stdio-shim jars are standalone programs and stay
on the GitHub release. Each platform is optional: it is skipped, with a message, unless its token and project id
are in the environment (MODRINTH_TOKEN + MODRINTH_PROJECT_ID, CURSEFORGE_TOKEN + CURSEFORGE_PROJECT_ID).
With --dry-run nothing is sent: the requests are printed, and the tokens are not needed.
"""
import json
import os
import re
import sys
import urllib.request
import uuid
from pathlib import Path

JAR = re.compile(r"^minecraft-mcp-(?P<version>.+)\+(?P<mc>[^+-]+)-(?P<loader>fabric|neoforge)\.jar$")
LOADER_NAMES = {"fabric": "Fabric", "neoforge": "NeoForge"}
USER_AGENT = "aomarai/minecraft-mcp release (github.com/aomarai/minecraft-mcp)"


def java_for(mc):
    return 21 if mc.startswith("1.") else 25


def release_type(version):
    return "release" if re.fullmatch(r"\d+\.\d+\.\d+", version) else "beta"


def jars(dist, version):
    found = []
    for path in sorted(Path(dist).glob("minecraft-mcp-*.jar")):
        m = JAR.match(path.name)
        if m:
            if m["version"] != version:
                raise SystemExit(f"{path.name} is not version {version}")
            found.append((path, m["mc"], m["loader"]))
    return found


def multipart(fields, file_field, path):
    boundary = uuid.uuid4().hex
    body = b""
    for name, value in fields.items():
        body += (f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n'
                 f'Content-Type: application/json\r\n\r\n{value}\r\n').encode()
    body += (f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; filename="{path.name}"\r\n'
             'Content-Type: application/java-archive\r\n\r\n').encode() + path.read_bytes() + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    return body, f"multipart/form-data; boundary={boundary}"


def send(request):
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.loads(response.read() or b"{}")


def modrinth_data(project, version, mc, loader, notes):
    return {
        "name": f"{version} for {LOADER_NAMES[loader]} {mc}",
        "version_number": f"{version}+{mc}-{loader}",
        "changelog": notes,
        "dependencies": [{"project_id": "P7dR8mSH", "dependency_type": "required"}] if loader == "fabric" else [],
        "game_versions": [mc],
        "version_type": release_type(version),
        "loaders": [loader],
        "featured": False,
        "project_id": project,
        "file_parts": ["file"],
        "primary_file": "file",
    }


def publish_modrinth(items, version, notes, dry):
    token, project = os.environ.get("MODRINTH_TOKEN"), os.environ.get("MODRINTH_PROJECT_ID")
    if not dry and not (token and project):
        print("Modrinth: skipped (MODRINTH_TOKEN / MODRINTH_PROJECT_ID not set)")
        return
    for path, mc, loader in items:
        data = modrinth_data(project or "<project>", version, mc, loader, notes)
        if dry:
            print("Modrinth:", path.name, json.dumps({**data, "changelog": "..."}))
            continue
        body, ctype = multipart({"data": json.dumps(data)}, "file", path)
        send(urllib.request.Request("https://api.modrinth.com/v2/version", body, {
            "Authorization": token, "Content-Type": ctype, "User-Agent": USER_AGENT}))
        print("Modrinth: uploaded", path.name)


def curseforge_game_version_ids(names, game_versions):
    by_name = {g["name"]: g["id"] for g in game_versions}
    missing = [n for n in names if n not in by_name]
    if missing:
        raise SystemExit(f"CurseForge does not know these game versions: {missing}")
    return [by_name[n] for n in names]


def publish_curseforge(items, version, notes, dry):
    token, project = os.environ.get("CURSEFORGE_TOKEN"), os.environ.get("CURSEFORGE_PROJECT_ID")
    if not dry and not (token and project):
        print("CurseForge: skipped (CURSEFORGE_TOKEN / CURSEFORGE_PROJECT_ID not set)")
        return
    base = "https://minecraft.curseforge.com/api"
    known = [] if dry else send(urllib.request.Request(f"{base}/game/versions", headers={
        "X-Api-Token": token, "User-Agent": USER_AGENT}))
    for path, mc, loader in items:
        names = [mc, LOADER_NAMES[loader], f"Java {java_for(mc)}"]
        meta = {"changelog": notes, "changelogType": "markdown", "displayName": f"{version} ({LOADER_NAMES[loader]} {mc})",
                "releaseType": release_type(version),
                "gameVersions": names if dry else curseforge_game_version_ids(names, known)}
        if dry:
            print("CurseForge:", path.name, json.dumps({**meta, "changelog": "..."}))
            continue
        body, ctype = multipart({"metadata": json.dumps(meta)}, "file", path)
        send(urllib.request.Request(f"{base}/projects/{project}/upload-file", body, {
            "X-Api-Token": token, "Content-Type": ctype, "User-Agent": USER_AGENT}))
        print("CurseForge: uploaded", path.name)


def main(argv):
    args = [a for a in argv if a != "--dry-run"]
    dry = len(args) != len(argv)
    if len(args) != 3:
        raise SystemExit(__doc__)
    dist, version, notes_file = args
    items = jars(dist, version)
    if not items:
        raise SystemExit(f"no loader jars for {version} in {dist}")
    notes = Path(notes_file).read_text()
    publish_modrinth(items, version, notes, dry)
    publish_curseforge(items, version, notes, dry)


if __name__ == "__main__":
    main(sys.argv[1:])
