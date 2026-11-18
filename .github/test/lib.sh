#!/bin/bash
# Shared functions of the container harnesses breaking-changes.sh and access-changes.sh. run.sh starts them.
# Mounts: /wf/openapi-change-control.yml (the workflow), /h (this folder), /project.tar (the project, only for
# access-changes.sh), /cache (a Maven folder that the cases share, only for access-changes.sh).

WF=/wf/openapi-change-control.yml
OAS=gateway/src/main/resources/openapi
LABEL_AT=2026-11-15T12:00:00Z
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
export GIT_COMMITTER_DATE=2026-11-15T10:00:00Z GIT_AUTHOR_DATE=2026-11-15T10:00:00Z
JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")

passed=0 failed=0 results=()

# ---------- tools: yq with the pinned checksum of the workflow, to read the workflow ----------
bootstrap() {
  local version sum
  version=$(sed -n 's/^ *YQ_VERSION: //p' "$WF" | head -1)
  sum=$(sed -n 's/^ *YQ_SHA256: //p' "$WF" | head -1)
  mkdir -p /tmp/boot
  curl -fsSL -o /tmp/boot/yq "https://github.com/mikefarah/yq/releases/download/v${version}/yq_linux_amd64"
  echo "$sum  /tmp/boot/yq" | sha256sum --check --strict - || exit 1
  chmod +x /tmp/boot/yq
  YQ=/tmp/boot/yq
}

# The run: script of the step named $2 of job $1, with the YAML anchors resolved.
step_script() {
  "$YQ" "explode(.) | .jobs[\"$1\"].steps[] | select(.name == \"$2\") | .run" "$WF"
}

# Runs the install step of job $1 with the env of the job and the checksums $2 (oasdiff) and $3 (yq). Sets OUTCOME and
# LOG, and copies the tools to /tmp/tools after a pass.
install_tools() {
  local job=$1 tmp
  tmp=$(mktemp -d /tmp/inst.XXXX)
  LOG=$tmp/log
  step_script "$job" "Install oasdiff and yq" > "$tmp/step.sh"
  local -a env_args
  mapfile -t env_args < <("$YQ" "explode(.) | .jobs[\"$job\"].env | to_entries | .[] | .key + \"=\" + (.value | tostring)" "$WF")
  env -i PATH=/usr/bin:/bin HOME=/root RUNNER_TEMP="$tmp" "${env_args[@]}" OASDIFF_SHA256="$2" YQ_SHA256="$3" \
    bash --noprofile --norc -eo pipefail "$tmp/step.sh" > "$LOG" 2>&1 && OUTCOME=pass || OUTCOME=fail:install
  if [ "$OUTCOME" = pass ]; then
    mkdir -p /tmp/tools && cp "$tmp/tools/oasdiff" "$tmp/tools/yq" /tmp/tools/
    "$tmp/tools/oasdiff" --version >> "$LOG" 2>&1; "$tmp/tools/yq" --version >> "$LOG" 2>&1
  fi
}
checksum() { "$YQ" "explode(.) | .jobs[\"$1\"].env.$2" "$WF"; }

# ---------- repository builder ----------
# new_repo [project]: an empty repository, or with the files of /project.tar.
new_repo() {
  R=$(mktemp -d /tmp/repo.XXXX)
  git -C "$R" init -q -b main
  git -C "$R" commit -q --allow-empty -m init
  if [ "${1:-}" = project ]; then
    tar -xf /project.tar -C "$R"
  fi
}
put() { mkdir -p "$(dirname "$R/$1")"; cat > "$R/$1"; }
del() { git -C "$R" rm -q -r -- "$1"; }
commit_base() { git -C "$R" add -A && git -C "$R" commit -q --allow-empty -m base; git -C "$R" checkout -q -b head; }
# Commits the head and builds the merge commit refs/pull/1/merge (parent 1: base, parent 2: head).
commit_head() {
  git -C "$R" add -A && git -C "$R" commit -q --allow-empty -m head
  HEAD_SHA=$(git -C "$R" rev-parse head)
  git -C "$R" checkout -q --detach main
  git -C "$R" merge -q --no-ff --no-edit head > /dev/null
  git -C "$R" update-ref refs/pull/1/merge HEAD
}

# ---------- label fixtures for the gh stub ----------
# fixtures <label> <variant>: valid, none, unreadable, removed, stale, triage, forged-commit-date, other-head,
# old-branch-activity, or other-label.
FIX=""
fixtures() {
  FIX=$(mktemp -d /tmp/fix.XXXX)
  local label=$1 actor=maint activity_at=2026-11-15T11:00:00Z after=$HEAD_SHA commit_at=2026-11-15T10:00:00Z
  local pr_head=$HEAD_SHA labels
  case "$2" in
    none) echo "{\"labels\":[],\"head\":{\"sha\":\"$HEAD_SHA\"}}" > "$FIX/pulls.json"; return 0 ;;
    unreadable) return 0 ;;
    stale) activity_at=2026-11-15T12:30:00Z ;;
    triage) actor=triager ;;
    forged-commit-date) commit_at=2026-11-15T13:00:00Z ;;
    other-head) pr_head=0000000000000000000000000000000000000001 ;;
    old-branch-activity) after=0000000000000000000000000000000000000002 ;;
    other-label) [ "$label" = breaking-change-approved ] && label=access-change-approved || label=breaking-change-approved ;;
  esac
  labels="[{\"name\":\"$label\"}]"
  [ "$2" = removed ] && labels='[]'
  cat > "$FIX/pulls.json" <<JSON
{"number":1,"labels":$labels,"head":{"sha":"$pr_head","ref":"feature/x","repo":{"full_name":"o/r"}}}
JSON
  cat > "$FIX/timeline.json" <<JSON
[{"event":"committed","sha":"$HEAD_SHA","committer":{"date":"$commit_at"}},
 {"event":"labeled","created_at":"2026-11-15T09:00:00Z","actor":{"login":"maint"},"label":{"name":"$label"}},
 {"event":"labeled","created_at":"$LABEL_AT","actor":{"login":"$actor"},"label":{"name":"$label"}},
 {"event":"labeled","created_at":"2026-11-15T12:10:00Z","actor":{"login":"maint"},"label":{"name":"other"}}]
JSON
  echo "[{\"timestamp\":\"$activity_at\",\"activity_type\":\"push\",\"ref\":\"refs/heads/feature/x\",\"after\":\"$after\"}]" > "$FIX/activity.json"
  echo '{"permission":"admin","role_name":"maintain"}' > "$FIX/perm_maint.json"
  echo '{"permission":"read","role_name":"triage"}' > "$FIX/perm_triager.json"
}

# ---------- job runner ----------
# The short name of a step in OUTCOME.
slug() {
  case "$1" in
    "Check the merge commit") echo merge ;;
    "Check the location of the OAS files") echo location ;;
    "Extract base and head OAS files") echo extract ;;
    "Build the CLI from the base commit") echo build ;;
    "Read the label approval") echo label ;;
    "Access diff") echo diff ;;
    "oasdiff breaking") echo oasdiff ;;
    *) echo "harness: no short name for the step '$1'" >&2; exit 2 ;;
  esac
}

# The value $2 of the GITHUB_OUTPUT of the step with the id $1. Empty when the step did not run.
output_of() { sed -n "s/^$2=//p" "$TMP/outputs/$1" 2>/dev/null | tail -1; }

# The value of an env entry of the workflow: a literal, or one ${{ }} expression that the harness knows.
expand() {
  local value=$1 expr id
  case "$value" in
    '${{ '*' }}') expr=${value#'${{ '}; expr=${expr%' }}'} ;;
    *'${{'*) echo "harness: unsupported expression in '$value'" >&2; exit 2 ;;
    *) printf '%s' "$value"; return 0 ;;
  esac
  case "$expr" in
    github.event.pull_request.head.sha) printf '%s' "$JOB_HEAD" ;;
    github.event.pull_request.number) printf 1 ;;
    github.token) printf x ;;
    steps.*.outputs.*) id=${expr#steps.}; id=${id%%.*}; output_of "$id" "${expr##*.}" ;;
    *) echo "harness: unsupported expression '$expr'" >&2; exit 2 ;;
  esac
}

# True if the if: condition of a step holds. The harness knows: no condition, and
# steps.<id>.outputs.<name> == '<value>'. Another condition stops the harness.
condition() {
  local pattern="^steps\.([A-Za-z0-9_-]+)\.outputs\.([A-Za-z0-9_-]+) == '([^']*)'$"
  if [ -z "$1" ]; then
    return 0
  fi
  if [[ $1 =~ $pattern ]]; then
    [ "$(output_of "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}")" = "${BASH_REMATCH[3]}" ]
    return
  fi
  echo "harness: unsupported if: $1" >&2
  exit 2
}

# Called after each step that passes, with its short name. A harness can replace it.
after_step() { :; }

# run_job <job>: runs the steps of the job like GitHub Actions, in their order in the workflow. A step whose if:
# condition is false does not run. The first failed step stops the job. The env of each step is the env of the workflow,
# of the job, and of the step, with the ${{ }} expressions resolved. The harness does the checkout, the JDK of the
# image replaces setup-java, and the tools of install_tools replace the install step.
# Sets OUTCOME (pass or fail:<step>), LOG, and TMP (the RUNNER_TEMP of the run).
run_job() {
  local job=$1 ws step name uses cond count i
  JOB_HEAD=${JOB_HEAD_SHA:-$HEAD_SHA}
  ws=$(mktemp -d /tmp/ws.XXXX) TMP=$(mktemp -d /tmp/rt.XXXX)
  LOG=$TMP/log
  mkdir -p "$TMP/outputs" "$TMP/home" "$TMP/tools"
  git -C "$ws" init -q
  git -C "$ws" fetch -q --depth 2 "file://$R" refs/pull/1/merge
  git -C "$ws" checkout -q --detach FETCH_HEAD
  cp /tmp/tools/oasdiff /tmp/tools/yq "$TMP/tools/"
  # The Maven folder of the build step, shared between the cases for speed.
  if [ -d /cache ]; then
    mkdir -p /cache/maven && ln -s /cache/maven "$TMP/maven"
  fi
  [ -n "$FIX" ] || fixtures "$FIX_LABEL" none
  local -a base_env
  mapfile -t base_env < <("$YQ" "explode(.) | (.env // {}) * (.jobs[\"$job\"].env // {}) | to_entries | .[] | .key + \"=\" + (.value | tostring)" "$WF")
  count=$("$YQ" "explode(.) | .jobs[\"$job\"].steps | length" "$WF")
  OUTCOME=pass
  for ((i = 0; i < count; i++)); do
    step=$("$YQ" -o json -I 0 "explode(.) | .jobs[\"$job\"].steps[$i]" "$WF")
    name=$(jq -r '.name // ""' <<< "$step")
    uses=$(jq -r '.uses // ""' <<< "$step")
    cond=$(jq -r '.if // ""' <<< "$step")
    if ! condition "$cond"; then
      echo "--- skipped: ${name:-$uses} (if: $cond)" >> "$LOG"
      continue
    fi
    if [ -n "$uses" ] || [ "$name" = "Install oasdiff and yq" ]; then
      echo "--- harness: ${name:-$uses}" >> "$LOG"
      continue
    fi
    local id key value
    local -a step_env=()
    id=$(jq -r '.id // ""' <<< "$step")
    while IFS=$'\t' read -r key value; do
      step_env+=("$key=$(expand "$value")")
    done < <(jq -r '.env // {} | to_entries[] | [.key, (.value | tostring)] | @tsv' <<< "$step")
    jq -r '.run' <<< "$step" > "$TMP/step.sh"
    : > "$TMP/out"
    echo "--- step: $name" >> "$LOG"
    if ! (cd "$ws" && env -i PATH="/h/stub:$JAVA_HOME/bin:/usr/bin:/bin" HOME="$TMP/home" JAVA_HOME="$JAVA_HOME" \
      RUNNER_TEMP="$TMP" GITHUB_WORKSPACE="$ws" GITHUB_STEP_SUMMARY="$TMP/summary.md" GITHUB_REPOSITORY=o/r \
      GH_FIXTURES="$FIX" GITHUB_OUTPUT="$TMP/out" "${base_env[@]}" "${step_env[@]}" \
      bash --noprofile --norc -eo pipefail "$TMP/step.sh") >> "$LOG" 2>&1; then
      OUTCOME=fail:$(slug "$name")
      return
    fi
    [ -n "$id" ] && cp "$TMP/out" "$TMP/outputs/$id"
    after_step "$(slug "$name")"
  done
}

# expect <case name> <outcome> [extended regular expression that must be in the log, or !expression that must not]...
expect() {
  local name=$1 want=$2 ok=1 pat; shift 2
  [ "$OUTCOME" = "$want" ] || ok=0
  for pat in "$@"; do
    if [[ $pat == '!'* ]]; then
      grep -qE -- "${pat#!}" "$LOG" && ok=0
    else
      grep -qE -- "$pat" "$LOG" || ok=0
    fi
  done
  if [ "$ok" = 1 ]; then
    passed=$((passed + 1)); results+=("PASS  $name ($OUTCOME)")
  else
    failed=$((failed + 1)); results+=("FAIL  $name (got $OUTCOME, want $want)")
    echo "===== log of $name"; cat "$LOG"
  fi
  FIX="" JOB_HEAD_SHA="" TAMPER=""
}

report() {
  printf '%s\n' "${results[@]}"
  echo "TOTAL: $passed passed, $failed failed"
  [ "$failed" -eq 0 ]
}
