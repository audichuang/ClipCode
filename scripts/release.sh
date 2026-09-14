#!/usr/bin/env bash
# Release ClipCode in one command: preflight -> tag -> wait for CI -> verify the
# zip is really attached to the GitHub Release.
#
# Prints exactly ONE line to stdout and exits 0 only when the release is live,
# so a caller never has to read the log or interpret anything:
#
#     OK v1.2.9 live: https://github.com/...
#     FAIL: <reason>
#
# Everything else goes to stderr. Tests are the caller's job -- CI runs
# `buildPlugin signPlugin`, never `build`, so a tag push says nothing about the
# JUnit suite. Run `./gradlew build` yourself first.
set -euo pipefail

cd "$(dirname "$0")/.."
V="${1:-}"
say() { printf '%s\n' "$*" >&2; }
die() { printf 'FAIL: %s\n' "$*"; exit 1; }

[ -n "$V" ] || die "usage: scripts/release.sh <version>, e.g. 1.2.9"

# --- preflight: refuse rather than ship something half-prepared --------------
[ "$(git rev-parse --abbrev-ref HEAD)" = "main" ] || die "not on main (releases come off main)"
git diff-index --quiet HEAD -- || die "working tree is dirty -- commit or stash first"
git fetch --quiet origin main --tags
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || die "main and origin/main differ -- push or pull first"

grep -q "^pluginVersion=$V\$" gradle.properties \
    || die "gradle.properties pluginVersion is not $V (it is the only place the version lives)"
grep -q "<h2>Version $V" build.gradle.kts \
    || die "build.gradle.kts changeNotes has no '<h2>Version $V' block -- the Release body would fall back to the whole cumulative history"

if git rev-parse -q --verify "refs/tags/v$V" >/dev/null; then die "tag v$V already exists locally"; fi
if git ls-remote --exit-code --tags origin "refs/tags/v$V" >/dev/null 2>&1; then die "tag v$V already exists on origin"; fi

# --- ship --------------------------------------------------------------------
say "# tagging v$V and pushing"
git tag "v$V"
git push --quiet origin "v$V"

say "# waiting for the Release workflow"
run=""
for _ in $(seq 12); do
    run=$(gh run list --workflow Release --branch "v$V" --limit 1 --json databaseId -q '.[0].databaseId' 2>/dev/null || true)
    if [ -n "$run" ]; then break; fi
    sleep 5
done
[ -n "$run" ] || die "no workflow run appeared for v$V within 60s -- check GitHub Actions"
gh run watch "$run" --exit-status >&2 || die "Release workflow failed -- gh run view $run --log-failed"

# --- the only thing that counts: users install this zip by hand --------------
gh release view "v$V" --json assets -q '.assets[].name' 2>/dev/null | grep -qx "ClipCode-$V.zip" \
    || die "workflow went green but ClipCode-$V.zip is not attached to the v$V release"

printf 'OK v%s live: %s\n' "$V" "$(gh release view "v$V" --json url -q .url)"
