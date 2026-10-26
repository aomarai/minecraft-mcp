#!/usr/bin/env python3
"""Writes the duration of every finished job of a workflow run as a Markdown table, and warns when the run took longer
than its budget, so a CI slowdown shows up on the run page instead of needing someone to read logs.

    duration-summary.py <owner/repo> <run id>

Reads GH_TOKEN from the environment. Prints Markdown on stdout (append it to $GITHUB_STEP_SUMMARY) and a workflow
warning on stderr when over budget. Never fails the build: the caller runs it with continue-on-error.
"""
import json
import os
import sys
import urllib.request
from datetime import datetime

# The wall time of a green pipeline is about 7.5 minutes (docs/ci.md); the budget leaves room for noisy runners.
BUDGET_MINUTES = 11


def parse(stamp):
    return datetime.fromisoformat(stamp.replace("Z", "+00:00"))


def finished_jobs(repo, run_id):
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/jobs?per_page=100",
        headers={"Authorization": f"Bearer {os.environ['GH_TOKEN']}", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        jobs = json.load(response)["jobs"]
    return [j for j in jobs if j["started_at"] and j["completed_at"]]


def main(repo, run_id):
    jobs = finished_jobs(repo, run_id)
    if not jobs:
        return
    wall = (max(parse(j["completed_at"]) for j in jobs) - min(parse(j["started_at"]) for j in jobs)).total_seconds()
    rows = sorted(((parse(j["completed_at"]) - parse(j["started_at"])).total_seconds(), j["name"]) for j in jobs)
    print(f"### Job durations (wall time so far: {wall / 60:.1f} min, budget {BUDGET_MINUTES} min)\n")
    print("| Job | Minutes |\n|---|---|")
    for seconds, name in reversed(rows):
        print(f"| {name} | {seconds / 60:.1f} |")
    if wall > BUDGET_MINUTES * 60:
        print(f"::warning title=Slow CI run::The pipeline took {wall / 60:.1f} minutes, over the {BUDGET_MINUTES} minute "
              "budget; see the job durations in the run summary.", file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
