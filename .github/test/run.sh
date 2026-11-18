#!/bin/bash
# Runs the container harnesses of the workflow .github/workflows/openapi-change-control.yml: breaking-changes.sh
# (job breaking-changes, spec 033) and access-changes.sh (job access-changes, spec 034). Needs Docker and the network.
# CI does not run it. See specs/034-access-change-review.md, "Test harness".
#
# Usage: .github/test/run.sh [breaking-changes | access-changes]...
#        ONLY='c_location' .github/test/run.sh access-changes     (only the named cases, after the install)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(git -C "$here" rev-parse --show-toplevel)
image=oas-change-control-test
harnesses=("$@")
[ "${#harnesses[@]}" -gt 0 ] || harnesses=(breaking-changes access-changes)

docker build --platform linux/amd64 -q -t "$image" "$here" > /dev/null
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# The project of the working tree: the tracked files and the new files that Git does not ignore.
# COPYFILE_DISABLE and --no-xattrs: no macOS metadata in the archive.
(cd "$root" && git ls-files -z --cached --others --exclude-standard \
  | COPYFILE_DISABLE=1 tar --no-xattrs --null -T - -cf "$tmp/project.tar")

status=0
for harness in "${harnesses[@]}"; do
  echo "== $harness"
  # The volume keeps the Maven folder of the build step between the runs, for speed.
  docker run --rm --platform linux/amd64 -e ONLY="${ONLY:-}" \
    -v "$here:/h:ro" -v "$root/.github/workflows/openapi-change-control.yml:/wf/openapi-change-control.yml:ro" \
    -v "$tmp/project.tar:/project.tar:ro" -v oas-change-control-maven:/cache \
    "$image" bash /h/"$harness".sh || status=1
done
exit "$status"
