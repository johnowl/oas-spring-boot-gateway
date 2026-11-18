#!/bin/bash
# Runs the run: scripts of the job access-changes (spec 034) on crafted Git repositories with real merge commits.
# Each repository holds the project of /project.tar, so that the job really builds the CLI from the base commit.
# Start it with run.sh. See specs/034-access-change-review.md, "Test harness".
set -uo pipefail
. /h/lib.sh
FIX_LABEL=access-change-approved

# orders.yaml: $1 all-of of DELETE, $2 upstream, $3 extra lines at the end of x-gateway (optional)
orders() {
  cat <<EOF
openapi: 3.0.3
info:
  title: Orders
  version: 1.0.0
x-gateway:
  upstream: $2
  prefix: /orders-api
  audience: 6f1c2e8a-0000-0000-0000-000000000000
${3:-}
components:
  securitySchemes:
    entra:
      type: openIdConnect
      openIdConnectUrl: https://login.microsoftonline.com/tenant/v2.0/.well-known/openid-configuration
security:
  - entra: []
paths:
  /orders/{id}:
    parameters:
      - name: id
        in: path
        required: true
        schema:
          type: string
    get:
      x-gateway-authorization:
        all-of: [Orders.Read]
      responses:
        "200":
          description: OK
    delete:
      x-gateway-authorization:
        all-of: [$1]
      responses:
        "200":
          description: OK
EOF
}
UP=http://orders-service:8080
base_orders() { orders "Orders.Read, Orders.Delete" "$UP" | put $OAS/orders.yaml; }

# ---------- cases ----------
step_field() { "$YQ" "explode(.) | .jobs[\"$1\"].steps[] | select(.name == \"$2\") | .$3" "$WF"; }
c_shared_scripts() {
  local step ok=1
  LOG=$(mktemp)
  for step in "Install oasdiff and yq" "Check the merge commit" "Check the location of the OAS files" \
    "Extract base and head OAS files" "Read the label approval"; do
    if ! cmp -s <(step_script breaking-changes "$step") <(step_script access-changes "$step") \
      || [ -z "$(step_script access-changes "$step")" ]; then
      echo "different script: $step" >> "$LOG"; ok=0
    fi
    if ! cmp -s <(step_field breaking-changes "$step" if) <(step_field access-changes "$step" if); then
      echo "different if: $step" >> "$LOG"; ok=0
    fi
  done
  cmp -s <("$YQ" -o json 'explode(.) | .jobs["access-changes"].env' "$WF") \
    <("$YQ" -o json 'explode(.) | .jobs["breaking-changes"].env' "$WF") || { echo "different env" >> "$LOG"; ok=0; }
  [ "$ok" = 1 ] && OUTCOME=pass || OUTCOME=fail
  expect "access-changes runs the same scripts and env as breaking-changes" pass
}
c_install() {
  install_tools access-changes "$(checksum access-changes OASDIFF_SHA256)" "$(checksum access-changes YQ_SHA256)"
  expect "install step with the pinned checksums" pass "oasdiff.tar.gz: OK" "yq: OK"
}
c_no_oas_change() {
  new_repo project; base_orders; commit_base; put README.md <<< "changed"; commit_head
  run_job access-changes; expect "no OAS change" pass "Result: unchanged" "does not change the OAS files" "!--- step: Build the CLI" \
    "--- skipped: Build the CLI from the base commit \(if: steps.oas.outputs.result == 'compare'\)" "--- skipped: Read the label approval"
}
c_loosening() { # $1 fixture, $2 expected outcome, then patterns
  local fix=$1 want=$2; shift 2
  new_repo project; base_orders; commit_base; orders "Orders.Read" "$UP" | put $OAS/orders.yaml; commit_head
  fixtures "$FIX_LABEL" "$fix"; run_job access-changes; summary_rows
  expect "loosening change, label fixture $fix" "$want" \
    "::error file=$OAS/orders.yaml,line=[0-9]+,col=[0-9]+::Access loosening \(permission-removed-from-all-of\) of DELETE /orders-api/orders/\{id\}" \
    "The CLI of the base commit [0-9a-f]{40} has the SHA-256 [0-9a-f]{64}" "$@"
}
c_binding() { # $1 fixture, $2 expected outcome, then patterns
  local fix=$1 want=$2; shift 2
  new_repo project; base_orders; commit_base
  orders "Orders.Read, Orders.Delete" http://reporting-service:8080 | put $OAS/orders.yaml; commit_head
  fixtures "$FIX_LABEL" "$fix"; run_job access-changes
  grep -q '^| binding-changed | ` 6f1c2e8a-0000-0000-0000-000000000000 ` | ` http://orders-service:8080 ` | ` http://reporting-service:8080 `' \
    "$TMP/summary.md" 2>/dev/null && echo "SUMMARY-TABLE-OK" >> "$LOG"
  expect "changed upstream (binding), label fixture $fix" "$want" "Audience binding changed: 6f1c2e8a" "SUMMARY-TABLE-OK" \
    "!Access loosening" "$@"
}
c_first_audience() {
  new_repo project; commit_base; base_orders; commit_head
  run_job access-changes; expect "first OAS file with an audience, empty base, no label" fail:diff "New audience binding: 6f1c2e8a" \
    "loosens access or adds or changes an audience binding"
}
c_invalid_head() {
  new_repo project; base_orders; commit_base
  orders "Orders.Read, Orders.Delete" "$UP" | sed '/x-gateway-authorization:/,+1d' | put $OAS/orders.yaml; commit_head
  fixtures "$FIX_LABEL" valid; run_job access-changes
  expect "head file with a CONTRACT error, valid label" fail:diff "Label access-change-approved counts" \
    "the head directory has files with errors" "diff exit code 2: an OAS file cannot be compared. The label does not change" \
    "::notice::diff of the base commit checks the head files with the CONTRACT rules of the base"
}
c_pr_changes_cli() {
  local cmd=openapi-gateway-cli/src/main/java/com/johnowl/gateway/cli/DiffCommand.java
  local core=openapi-gateway-core/src/main/java/com/johnowl/gateway/core/access/AccessDiff.java
  new_repo project; base_orders; commit_base
  orders "Orders.Read" "$UP" | put $OAS/orders.yaml
  # The head makes diff return 0 and print nothing, and tries to change the build of the CLI.
  sed -i 's/return result.changes().isEmpty() && result.bindings().isEmpty() ? ExitCodes.OK : ExitCodes.VIOLATIONS;/return ExitCodes.OK;/' "$R/$cmd"
  sed -i 's/case GITHUB -> DiffReport.writeGithub(result, new SourceLocator(), out);/case GITHUB -> { }/' "$R/$cmd"
  grep -q 'return ExitCodes.OK;' "$R/$cmd" || { echo "sed of $cmd failed"; exit 1; }
  echo "// changed by the pull request" >> "$R/$core"
  printf '%s export-ignore\n' "$OAS/orders.yaml" ".mvn" | put .gitattributes
  printf -- '-Dmaven.test.skip=true\n' | put .mvn/maven.config
  printf '#!/bin/sh\necho "mvnw of the pull request" >&2\nexit 0\n' | put mvnw
  commit_head
  run_job access-changes
  # The source of the build is the base: the head changes are not in it.
  grep -q 'ExitCodes.OK : ExitCodes.VIOLATIONS' "$TMP/cli-source/$cmd" && ! grep -q 'changed by the pull request' "$TMP/cli-source/$core" \
    && ! [ -e "$TMP/cli-source/.mvn/maven.config" ] && echo "BUILD-SOURCE-IS-BASE" >> "$LOG"
  # Control: the CLI of the head returns 0 for the same files, so the case would pass with a CLI of the pull request.
  local head_src; head_src=$(mktemp -d /tmp/headsrc.XXXX)
  git -C "$R" archive head | tar -x -C "$head_src"
  # The head .gitattributes removes .mvn from the archive: the control build takes the wrapper of the base.
  rm -rf "$head_src/mvnw" "$head_src/.mvn" && cp -r "$TMP/cli-source/mvnw" "$TMP/cli-source/.mvn" "$head_src/"
  (cd "$head_src" && MAVEN_USER_HOME=/cache/maven ./mvnw -B -q -Dmaven.repo.local=/cache/maven/repository \
    -pl openapi-gateway-cli -am package -DskipTests) > /dev/null 2>&1
  (cd "$TMP/oas/head" && java -jar "$head_src/openapi-gateway-cli/target/oas-gateway-validate.jar" diff --format github \
    "../base/$OAS" "$OAS" > /dev/null 2>&1)
  echo "CONTROL-HEAD-CLI-EXIT=$?" >> "$LOG"
  expect "pull request changes the CLI code, mvnw, .mvn, and .gitattributes to hide a loosening change" fail:diff \
    "BUILD-SOURCE-IS-BASE" "CONTROL-HEAD-CLI-EXIT=0" "Access loosening \(permission-removed-from-all-of\)" "!mvnw of the pull request"
}
c_tampered_jar() {
  new_repo project; base_orders; commit_base; orders "Orders.Read" "$UP" | put $OAS/orders.yaml; commit_head
  fixtures "$FIX_LABEL" valid; TAMPER=1; run_job access-changes
  expect "jar changed after the build step, valid label" fail:diff "oas-gateway-validate.jar: FAILED" "!Access loosening"
}
c_exit1_without_summary() {
  # A fake java: the JVM fails with exit code 1 and writes nothing on stdout. The label counts.
  local tmp; tmp=$(mktemp -d /tmp/fake.XXXX)
  LOG=$tmp/log
  mkdir -p "$tmp/bin" "$tmp/oas/head/$OAS" "$tmp/oas/base/$OAS" "$tmp/cli"
  printf '#!/bin/sh\necho "Exception in thread main java.lang.NoClassDefFoundError" >&2\nexit 1\n' > "$tmp/bin/java"
  chmod +x "$tmp/bin/java"
  echo x > "$tmp/cli/oas-gateway-validate.jar"
  echo "openapi: 3.0.3" > "$tmp/oas/head/$OAS/a.yaml"
  step_script access-changes "Access diff" > "$tmp/step.sh"
  sha=$(sha256sum "$tmp/cli/oas-gateway-validate.jar"); sha=${sha%% *}
  env -i PATH="$tmp/bin:/usr/bin:/bin" RUNNER_TEMP="$tmp" OAS_DIR="$OAS" RESULT=compare APPROVED=true \
    CLI_JAR="$tmp/cli/oas-gateway-validate.jar" CLI_SHA256="$sha" bash --noprofile --norc -eo pipefail "$tmp/step.sh" >> "$LOG" 2>&1 \
    && OUTCOME=pass || OUTCOME=fail:diff
  expect "exit code 1 of the JVM without the summary line, valid label" fail:diff "exit code 1 without its summary line" "!counts"
}
c_all_removed() { # $1 fixture, $2 expected outcome, then patterns
  local fix=$1 want=$2; shift 2
  new_repo project; base_orders; commit_base; del $OAS/orders.yaml; commit_head
  fixtures "$FIX_LABEL" "$fix"; run_job access-changes
  expect "all OAS files removed, label fixture $fix" "$want" "removes all OAS files from $OAS" "$@"
}
c_symlink() {
  new_repo project; base_orders; commit_base; ln -s /etc/passwd "$R/$OAS/link.yaml"; commit_head
  fixtures "$FIX_LABEL" valid; run_job access-changes; expect "symbolic link in the head OAS folder" fail:extract "is a symbolic link or a submodule \(mode 120000\)"
}
c_location() {
  new_repo project; base_orders; commit_base
  printf 'gateway:\n  openapi:\n    specs: [file:/etc/oas/*.yaml]\n' | put gateway/src/main/resources/application-x.yaml; commit_head
  fixtures "$FIX_LABEL" valid; run_job access-changes; expect "pull request moves the OAS files" fail:location "changes gateway.openapi.specs"
}
c_not_merge() {
  new_repo project; base_orders; commit_base; commit_head
  JOB_HEAD_SHA=0000000000000000000000000000000000000003; run_job access-changes; expect "HEAD is not the merge commit of the head" fail:merge "is not the merge commit"
}

# Changes the jar after the build step, for the case "jar changed after the build step".
after_step() {
  if [ "$1" = build ] && [ -n "${TAMPER:-}" ]; then
    echo tampered >> "$(output_of cli jar)"
  fi
}
# Writes the line SUMMARY-ROWS=<n> to the log: the rows of the table of the loosening changes in the job summary.
summary_rows() {
  echo "SUMMARY-ROWS=$(grep -c '^| permission-removed-from-all-of |' "$TMP/summary.md" 2>/dev/null)" >> "$LOG"
}
c_many_changes() {
  local i
  new_repo project
  many() { # $1 all-of of each operation
    printf 'openapi: 3.0.3\ninfo: {title: Orders, version: 1.0.0}\n'
    printf 'x-gateway: {upstream: "%s", prefix: /orders-api, audience: 6f1c2e8a-0000-0000-0000-000000000000}\n' "$UP"
    printf 'components: {securitySchemes: {entra: {type: openIdConnect, openIdConnectUrl: "https://login.microsoftonline.com/t/v2.0/.well-known/openid-configuration"}}}\n'
    printf 'security: [{entra: []}]\npaths:\n'
    for i in $(seq 1 11); do
      printf '  /orders%s:\n    get:\n      x-gateway-authorization: {all-of: [%s]}\n      responses: {"200": {description: OK}}\n' "$i" "$1"
    done
  }
  many "Orders.Read, Orders.Delete" | put $OAS/orders.yaml; commit_base
  many "Orders.Read" | put $OAS/orders.yaml; commit_head
  fixtures "$FIX_LABEL" valid; run_job access-changes; summary_rows
  expect "11 loosening changes, valid label" pass "::warning::diff reports 11 changes and bindings, and GitHub shows only 10 annotations" \
    "SUMMARY-ROWS=11" "The label access-change-approved counts"
}
# A ~/.m2/settings.xml that Maven cannot parse, like a file of setup-java or of an earlier step: the build reads only
# the empty settings of -s and -gs (SEC-75-4). Maven reads the user.home of Java, not $HOME.
c_maven_settings() {
  local m2
  m2=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *user.home = //p')/.m2
  new_repo project; base_orders; commit_base; orders "Orders.Read" "$UP" | put $OAS/orders.yaml; commit_head
  mkdir -p "$m2" && echo '<settings><mirrors>' > "$m2/settings.xml"
  fixtures "$FIX_LABEL" valid; run_job access-changes
  rm -f "$m2/settings.xml"
  expect "broken ~/.m2/settings.xml, valid label" pass "BUILD SUCCESS" "!Non-parseable settings" "The label access-change-approved counts"
}

bootstrap
c_install
if [ -n "${ONLY:-}" ]; then
  eval "$ONLY"; report; exit
fi
c_shared_scripts
c_no_oas_change
c_loosening none fail:diff "does not count: The label access-change-approved is not present" "loosens access"
c_loosening valid pass "counts: maint \(maintain\) added the label after the last push" "The label access-change-approved counts" \
  "SUMMARY-ROWS=1" "!::warning::"
c_loosening stale fail:diff "added before the last push"
c_loosening forged-commit-date fail:diff "added before the last push"
c_loosening triage fail:diff "triager added the label with the role triage"
c_loosening unreadable fail:diff "The pull request cannot be read"
c_loosening other-label fail:diff "The label access-change-approved is not present"
c_binding none fail:diff "loosens access or adds or changes an audience binding"
c_binding valid pass "The label access-change-approved counts"
c_first_audience
c_invalid_head
c_pr_changes_cli
c_tampered_jar
c_exit1_without_summary
c_all_removed none fail:diff "::error::The pull request removes all OAS files" "does not count"
c_all_removed valid pass "::notice::The pull request removes all OAS files" "!::error"
c_many_changes
c_maven_settings
c_symlink
c_location
c_not_merge
report
