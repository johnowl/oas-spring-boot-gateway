#!/bin/bash
# Runs the run: scripts of the job breaking-changes (spec 033) on crafted Git repositories with real merge commits.
# Start it with run.sh. See specs/034-access-change-review.md, "Test harness".
set -uo pipefail
. /h/lib.sh
FIX_LABEL=breaking-change-approved

spec() { # $1 prefix ("" = none), then path lines
  local prefix=$1; shift
  echo "openapi: 3.1.0"
  echo "info: {title: t, version: '1'}"
  [ -n "$prefix" ] && echo "x-gateway: {prefix: $prefix, upstream: 'http://backend'}"
  echo "paths:"
  local p
  for p in "$@"; do
    echo "  $p:"
    echo "    get:"
    echo "      responses: {'200': {description: ok}}"
  done
}
spec_json() { # prefix path, JSON indented with tabs
  printf '{\n\t"openapi": "3.1.0",\n\t"info": {"title": "t", "version": "1"},\n\t"x-gateway": {"prefix": "%s", "upstream": "http://backend"},%s\n\t"paths": {"%s": {"get": {"responses": {"200": {"description": "ok"}}}}}\n}\n' "$1" "${3:-}" "$2"
}
spec_two_ops() { # prefix path: GET and DELETE
  printf "openapi: 3.1.0\ninfo: {title: t, version: '1'}\nx-gateway: {prefix: %s, upstream: 'http://backend'}\npaths:\n  %s:\n    get:\n      responses: {'200': {description: ok}}\n    delete:\n      responses: {'204': {description: ok}}\n" "$1" "$2"
}

# ---------- cases ----------
c_both_empty() {
  new_repo; put README.md <<< "x"; commit_base; put README.md <<< "y"; commit_head
  run_job breaking-changes; expect "both sides without OAS folder" pass "Result: unchanged"
}
c_no_base() {
  new_repo; commit_base; spec /a /items | put $OAS/a.yaml; commit_head
  run_job breaking-changes; expect "no base: head-only group against empty spec" pass "Result: compare, 1 changed"
}
c_unchanged() {
  new_repo; spec /a /items | put $OAS/a.yaml; commit_base; put README.md <<< "y"; commit_head
  run_job breaking-changes; expect "unchanged OAS files" pass "Result: unchanged" "does not change the OAS files"
}
c_remove_op() {
  new_repo; spec_two_ops /a /items | put $OAS/a.yaml; commit_base; spec /a /items | put $OAS/a.yaml; commit_head
  run_job breaking-changes; expect "removed operation, no label" fail:oasdiff "::error title=api-removed-without-deprecation" "does not count: The label"
}
c_label_matrix() { # $1 fixture, $2 expected outcome, $3 pattern
  new_repo; spec_two_ops /a /items | put $OAS/a.yaml; commit_base; spec /a /items | put $OAS/a.yaml; commit_head
  fixtures "$FIX_LABEL" "$1"; run_job breaking-changes; expect "label: $1" "$2" "$3"
}
c_remove_all() { # $1 fixture
  new_repo; spec /a /items | put $OAS/a.yaml; spec /b /orders | put $OAS/b.yaml; commit_base; del $OAS; commit_head
  fixtures "$FIX_LABEL" "$1"; run_job breaking-changes
  if [ "$1" = valid ]; then
    expect "all OAS files removed, valid label" pass "api-path-removed-without-deprecation" "counts: maint"
  else
    expect "all OAS files removed, no label" fail:oasdiff "GET /items API path removed" "GET /orders API path removed"
  fi
}
c_same_prefix_move() {
  new_repo; spec /a /items /other | put $OAS/a.yaml; commit_base
  spec /a /items | put $OAS/a.yaml; spec /a /other | put $OAS/a2.yaml; commit_head
  run_job breaking-changes; expect "move within the same prefix" pass "Result: compare, 1 changed" "!::error"
}
c_cross_prefix_move() {
  new_repo; spec /a /items /other | put $OAS/a.yaml; spec /b /orders | put $OAS/b.yaml; commit_base
  spec /a /items | put $OAS/a.yaml; spec /b /orders /other | put $OAS/b.yaml; commit_head
  run_job breaking-changes; expect "move to another prefix" fail:oasdiff "GET /other API path removed"
}
c_prefix_change() {
  new_repo; spec /a /items | put $OAS/a.yaml; commit_base; spec /a2 /items | put $OAS/a.yaml; commit_head
  run_job breaking-changes; expect "prefix change" fail:oasdiff "GET /items API path removed" "Result: compare, 2 changed"
}
c_one_sided_new_group() {
  new_repo; spec /a /items | put $OAS/a.yaml; commit_base
  spec_json /b /orders | put $OAS/b.json; commit_head
  run_job breaking-changes; expect "new prefix group in the head only (JSON with tabs)" pass "Result: compare, 1 changed" "!::error"
}
c_one_sided_removed_group() {
  new_repo; spec /a /items | put $OAS/a.yaml; spec "" /health | put $OAS/root.yml; commit_base
  del $OAS/root.yml; commit_head
  run_job breaking-changes; expect "root group removed (base only)" fail:oasdiff "GET /health API path removed" "x-gateway.prefix null"
}
c_same_path_two_prefixes() {
  new_repo; spec /a /health | put $OAS/a.yaml; spec /b /health | put $OAS/b.yaml; commit_base
  spec /a /health /x | put $OAS/a.yaml; commit_head
  run_job breaking-changes; expect "same OAS path in two prefixes (no duplicate endpoint)" pass "!duplicate endpoint"
}
c_duplicate_same_prefix() {
  new_repo; spec /a /health | put $OAS/a.yaml; commit_base; spec /a /health | put $OAS/a2.yaml; commit_head
  fixtures "$FIX_LABEL" valid; run_job breaking-changes; expect "duplicate in one prefix, label does not help" fail:oasdiff "duplicate endpoint" "exit code 104"
}
c_since_head() {
  new_repo; spec /a /items | put $OAS/a.yaml; commit_base
  { spec /a /items; echo "x-extra: {list: [1, {x-since-date: '2026-01-01'}]}"; } | put $OAS/a.yaml; commit_head
  fixtures "$FIX_LABEL" valid; run_job breaking-changes; expect "x-since-date in head (label present)" fail:extract "::error file=$OAS/a.yaml,line=[0-9]+::The OAS file contains x-since-date"
}
c_since_base() {
  new_repo; spec_json /a /items '"x-since-date": "2026-01-01",' | put $OAS/a.json; commit_base
  spec_json /a /items | put $OAS/a.json; commit_head
  run_job breaking-changes; expect "x-since-date in base" fail:extract "base OAS file $OAS/a.json contains x-since-date"
}
c_symlink() { # $1 base|head
  new_repo; spec /a /items | put $OAS/a.yaml
  if [ "$1" = base ]; then ln -s /etc/passwd "$R/$OAS/link.yaml"; fi
  commit_base
  if [ "$1" = head ]; then ln -s ../../../../../README.md "$R/$OAS/link.yaml"; else git -C "$R" rm -q "$OAS/link.yaml"; fi
  commit_head
  run_job breaking-changes; expect "symbolic link OAS file in $1" fail:extract "link.yaml in commit [0-9a-f]+ is a symbolic link or a submodule \(mode 120000\)"
}
c_gitlink() {
  new_repo; spec /a /items | put $OAS/a.yaml; commit_base
  git -C "$R" update-index --add --cacheinfo "160000,$(git -C "$R" rev-parse main),$OAS/sub.yaml"
  git -C "$R" commit -q -m head; HEAD_SHA=$(git -C "$R" rev-parse head)
  git -C "$R" checkout -q --detach main; git -C "$R" merge -q --no-ff --no-edit head > /dev/null; git -C "$R" update-ref refs/pull/1/merge HEAD
  run_job breaking-changes; expect "gitlink OAS entry in head" fail:extract "sub.yaml in commit [0-9a-f]+ is a symbolic link or a submodule \(mode 160000\)"
}
c_folder_link() {
  new_repo; spec /a /items | put other/a.yaml; mkdir -p "$R/gateway/src/main/resources"; ln -s ../../../../other "$R/$OAS"; commit_base
  put README.md <<< "y"; commit_head
  run_job breaking-changes; expect "OAS folder is a symbolic link" fail:extract "$OAS is not a folder in commit [0-9a-f]+ \(mode 120000\)"
}
c_component_link() {
  new_repo; spec /a /items | put other/openapi/a.yaml; mkdir -p "$R/gateway/src/main"; commit_base
  ln -s ../../../other "$R/gateway/src/main/resources"; commit_head
  run_job breaking-changes; expect "path component is a symbolic link" fail:extract "gateway/src/main/resources is not a folder in commit [0-9a-f]+ \(mode 120000\)"
}
c_folder_file() {
  new_repo; put $OAS <<< "not a folder"; commit_base; put README.md <<< "y"; commit_head
  run_job breaking-changes; expect "OAS folder is a file" fail:extract "$OAS is not a folder in commit [0-9a-f]+ \(mode 100644\)"
}
c_export_attributes() {
  new_repo
  spec_two_ops /a /items | put $OAS/a.yaml
  { spec /b /orders; echo "x-commit: '\$Format:%H\$'"; } | put $OAS/b.yaml
  printf '%s export-ignore\n%s export-subst\n' "$OAS/a.yaml" "$OAS/b.yaml" | put .gitattributes
  commit_base
  spec /a /items | put $OAS/a.yaml; commit_head
  run_job breaking-changes; expect "export-ignore and export-subst in base .gitattributes" fail:oasdiff "DELETE /items API removed" "Result: compare, 1 changed"
}
c_export_subst_unchanged() {
  new_repo
  { spec /b /orders; echo "x-commit: '\$Format:%H\$'"; } | put $OAS/b.yaml
  printf '%s export-subst\n' "$OAS/b.yaml" | put .gitattributes
  commit_base; put README.md <<< "y"; commit_head
  run_job breaking-changes; expect "export-subst file is unchanged" pass "Result: unchanged"
}
c_dotfile_and_subfolder() {
  new_repo; spec /a /items | put $OAS/a.yaml; spec /a /hidden | put $OAS/.hidden.yaml; spec /a /sub | put $OAS/sub/s.yaml
  put $OAS/README.md <<< "doc"; commit_base
  del $OAS/.hidden.yaml; del $OAS/sub; put $OAS/README.md <<< "doc2"; commit_head
  run_job breaking-changes; expect "dot file is an OAS file, subfolder and README are not" fail:oasdiff "GET /hidden API path removed" "!GET /sub"
}
c_bad_prefix() {
  new_repo; spec /a /items | put $OAS/a.yaml; commit_base
  printf "openapi: 3.1.0\ninfo: {title: t, version: '1'}\nx-gateway: {prefix: [1]}\npaths: {}\n" | put $OAS/a.yaml; commit_head
  run_job breaking-changes; expect "x-gateway.prefix is not a string" fail:extract "x-gateway.prefix of head file $OAS/a.yaml is not one string"
}
c_multi_doc() {
  new_repo; spec /a /items | put $OAS/a.yaml; commit_base
  { spec /a /items; echo "---"; spec /b /items; } | put $OAS/a.yaml; commit_head
  run_job breaking-changes; expect "two YAML documents" fail:extract "is not one string"
}
c_external_ref() {
  new_repo; spec /a /items | put $OAS/a.yaml; commit_base
  printf "openapi: 3.1.0\ninfo: {title: t, version: '1'}\nx-gateway: {prefix: /a, upstream: 'http://b'}\npaths:\n  /items:\n    get:\n      responses: {'200': {\$ref: 'other.yaml#/r'}}\n" | put $OAS/a.yaml
  commit_head
  fixtures "$FIX_LABEL" valid; run_job breaking-changes; expect "external \$ref, label does not help" fail:oasdiff "exit code 123"
}
c_unloadable() {
  new_repo; spec /a /items | put $OAS/a.yaml; commit_base; printf 'openapi: 3.1.0\npaths: [\n' | put $OAS/a.yaml; commit_head
  run_job breaking-changes; expect "head file cannot be parsed" fail:extract "did not find expected node content|yaml"
}
c_unloadable_oas() {
  new_repo; spec /a /items | put $OAS/a.yaml; commit_base
  printf "openapi: 3.1.0\ninfo: 5\nx-gateway: {prefix: /a}\npaths: {}\n" | put $OAS/a.yaml; commit_head
  fixtures "$FIX_LABEL" valid; run_job breaking-changes; expect "oasdiff cannot load the head file, label does not help" fail:oasdiff "exit code 103"
}
c_install() { # $1 oasdiff checksum, $2 yq checksum, $3 expected, $4 the tool with the wrong checksum
  install_tools breaking-changes "$1" "$2"
  if [ "$3" = pass ]; then
    expect "install with the pinned checksums" pass "oasdiff version 1.38.0" "version v4.54.3"
  else
    expect "install with a wrong $4 checksum" fail:install "FAILED"
  fi
}
c_not_merge() {
  new_repo; spec /a /items | put $OAS/a.yaml; commit_base; commit_head
  JOB_HEAD_SHA=0000000000000000000000000000000000000003; run_job breaking-changes; expect "HEAD is not the merge commit of the head" fail:merge "is not the merge commit"
}
c_config() { # $1 name, $2 path, $3 expected, $4 content, $5 base content of application.yaml (optional)
  local name=$1 path=$2 want=$3 content=$4
  local base=${5:-'gateway:\n  # See specs/022-session-login.md\n  session:\n    store: memory\n'}
  new_repo; spec /a /items | put $OAS/a.yaml
  printf '%b' "$base" | put gateway/src/main/resources/application.yaml
  commit_base; printf '%b' "$content" | put "$path"; commit_head
  fixtures "$FIX_LABEL" valid; run_job breaking-changes
  if [ "$want" = pass ]; then
    expect "config: $name" pass "does not change the location"
  else
    expect "config: $name" fail:location "changes gateway.openapi.specs in a configuration file"
  fi
}
c_pom() { # $1 base targetPath, $2 head targetPath, $3 expected
  new_repo; spec /a /items | put $OAS/a.yaml
  pom() { printf '<project xmlns="http://maven.apache.org/POM/4.0.0">\n  <build>\n    <resources>\n      <resource><directory>src/main/resources</directory></resource>\n%b    </resources>\n  </build>\n</project>\n' "$1"; }
  pom "$1" | put gateway/pom.xml; commit_base; pom "$2" | put gateway/pom.xml; commit_head
  fixtures "$FIX_LABEL" valid; run_job breaking-changes
  if [ "$3" = pass ]; then
    expect "pom: ${4}" pass "does not change the location"
  else
    expect "pom: ${4}" fail:location "Maven resource with targetPath openapi"
  fi
}
c_workflow_oas_dir() { # $1 base has workflow (yes|no), $2 expected
  new_repo; spec /a /items | put $OAS/a.yaml
  if [ "$1" = yes ]; then put .github/workflows/openapi-change-control.yml < "$WF"; fi
  commit_base
  sed 's#OAS_DIR: gateway/src/main/resources/openapi#OAS_DIR: gateway/src/main/resources/other#' "$WF" | put .github/workflows/openapi-change-control.yml
  commit_head
  run_job breaking-changes
  if [ "$2" = pass ]; then
    expect "workflow OAS_DIR, base without the workflow" pass "does not change the location"
  else
    expect "workflow OAS_DIR changed" fail:location "changes OAS_DIR in .github/workflows/openapi-change-control.yml"
  fi
}
c_config_symlink() {
  new_repo; spec /a /items | put $OAS/a.yaml; commit_base
  mkdir -p "$R/gateway/src/main/resources"; ln -s /etc/hostname "$R/gateway/src/main/resources/application-x.yaml"; commit_head
  run_job breaking-changes; expect "config file is a symbolic link" fail:location "application-x.yaml is not a regular file in commit [0-9a-f]+ \(mode 120000\)"
}
# A change outside the OAS folder that the location check reads (SEC-75-1, SEC-75-2).
# c_location_change <name> <expected> <pattern> <commands for the base> <commands for the head>
c_location_change() {
  local name=$1 want=$2 pattern=$3
  new_repo; spec /a /items | put $OAS/a.yaml
  eval "$4"
  commit_base
  eval "$5"
  commit_head
  fixtures "$FIX_LABEL" valid; run_job breaking-changes
  if [ "$want" = pass ]; then
    expect "location: $name" pass "does not change the location" "!::error"
  else
    expect "location: $name" fail:location "$pattern"
  fi
}
# x-gateway.prefix through a merge key: the group of the head is /b, so GET /items of the group /a is removed.
c_prefix_merge_key() {
  new_repo; spec /a /items | put $OAS/a.yaml; commit_base
  printf "openapi: 3.1.0\ninfo: {title: t, version: '1'}\nx-defaults: &gw {prefix: /b, upstream: 'http://backend'}\nx-gateway:\n  <<: *gw\npaths:\n  /items:\n    get:\n      responses: {'200': {description: ok}}\n" | put $OAS/a.yaml
  commit_head
  run_job breaking-changes; expect "x-gateway.prefix through a merge key" fail:oasdiff "Result: compare, 2 changed" "GET /items API path removed"
}

bootstrap
o_sum=$(checksum breaking-changes OASDIFF_SHA256) y_sum=$(checksum breaking-changes YQ_SHA256)
bad=0000000000000000000000000000000000000000000000000000000000000000
c_install "$bad" "$y_sum" fail oasdiff
c_install "$o_sum" "$bad" fail yq
c_install "$o_sum" "$y_sum" pass
if [ -n "${ONLY:-}" ]; then
  eval "$ONLY"; report; exit
fi
c_both_empty
c_no_base
c_unchanged
c_remove_op
c_label_matrix valid pass "counts: maint \(maintain\) added the label after the last push"
c_label_matrix stale fail:oasdiff "added before the last push"
c_label_matrix forged-commit-date fail:oasdiff "added before the last push"
c_label_matrix triage fail:oasdiff "triager added the label with the role triage"
c_label_matrix unreadable fail:oasdiff "The pull request cannot be read"
c_label_matrix removed fail:oasdiff "The label breaking-change-approved is not present"
c_label_matrix other-head fail:oasdiff "newer head than this run"
c_label_matrix old-branch-activity fail:oasdiff "last activity of the head branch is not the push"
c_remove_all none
c_remove_all valid
c_same_prefix_move
c_cross_prefix_move
c_prefix_change
c_one_sided_new_group
c_one_sided_removed_group
c_same_path_two_prefixes
c_duplicate_same_prefix
c_since_head
c_since_base
c_symlink head
c_symlink base
c_gitlink
c_folder_link
c_component_link
c_folder_file
c_export_attributes
c_export_subst_unchanged
c_dotfile_and_subfolder
c_bad_prefix
c_multi_doc
c_external_ref
c_unloadable
c_unloadable_oas
c_not_merge
c_config "specs added in application.yaml" gateway/src/main/resources/application.yaml fail 'gateway:\n  openapi:\n    specs:\n      - file:/etc/oas/*.yaml\n'
c_config "specs in application-prod.properties (relaxed binding)" gateway/src/main/resources/application-prod.properties fail 'gateway.open-api.SPECS[0]=file:/x/*.yaml\n'
c_config "specs as a dotted key in config/application.yml" gateway/src/main/resources/config/application.yml fail 'spring.config.activate.on-profile: p\ngateway.openapi.specs: classpath:x/*.yml\n'
c_config "specs set to an empty list" gateway/src/main/resources/application.yaml fail 'gateway:\n  openapi:\n    specs: []\n'
c_config "specs in a test resource" gateway/src/test/resources/application-t.yaml pass 'gateway:\n  openapi:\n    specs: [classpath:t/*.yaml]\n'
c_config "unrelated change with the word specs" gateway/src/main/resources/application.yaml pass 'gateway:\n  # See specs/033 and specs/011\n  session:\n    store: redis\n'
c_config "specs added to a second file with the same value" gateway/src/main/resources/application-prod.yaml fail 'gateway:\n  openapi:\n    specs: [classpath:o/*.yaml]\n' 'gateway:\n  openapi:\n    specs: [classpath:o/*.yaml]\n'
c_config "existing specs unchanged, other key changed" gateway/src/main/resources/application.yaml pass 'gateway:\n  openapi:\n    specs: [classpath:o/*.yaml]\n  session: {store: redis}\n' 'gateway:\n  openapi:\n    specs: [classpath:o/*.yaml]\n  session: {store: memory}\n'
c_config_symlink
c_pom "" "      <resource><directory>src/main/oas</directory><targetPath>openapi</targetPath></resource>\n" fail "resource with targetPath openapi added"
c_pom "      <resource><directory>src/main/oas</directory><targetPath>openapi</targetPath></resource>\n" "      <resource><directory>src/main/other</directory><targetPath>openapi</targetPath></resource>\n" fail "openapi resource changed"
c_pom "" "      <resource><directory>src/main/static</directory><targetPath>static</targetPath></resource>\n" pass "unrelated resource added"
c_workflow_oas_dir yes fail
c_workflow_oas_dir no pass

c_location_change "OAS file in the openapi folder of another module (head)" fail "openapi-gateway-core/src/main/resources/openapi/x.yaml is in a folder src/main/resources/openapi outside" \
  "" "spec /a /x | put openapi-gateway-core/src/main/resources/openapi/x.yaml"
c_location_change "OAS file in the openapi folder of another module (base only)" fail "openapi-gateway-cli/src/main/resources/openapi/x.json is in a folder src/main/resources/openapi outside" \
  "spec /a /x | put openapi-gateway-cli/src/main/resources/openapi/x.json" "put README.md <<< y"
c_location_change "OAS file in the openapi folder of the root module" fail "src/main/resources/openapi/x.yml is in a folder src/main/resources/openapi outside" \
  "" "spec /a /x | put src/main/resources/openapi/x.yml"
c_location_change "symbolic link named openapi in another module" fail "other/src/main/resources/openapi is in a folder src/main/resources/openapi outside" \
  "" "mkdir -p \$R/other/src/main/resources; ln -s ../../../../$OAS \$R/other/src/main/resources/openapi"
c_location_change "OAS file in the test resources of the gateway" pass "" \
  "" "spec /a /x | put gateway/src/test/resources/openapi/x.yaml"
c_location_change "OAS file in a subfolder of the OAS folder" pass "" \
  "" "spec /a /x | put $OAS/sub/x.yaml"
c_location_change "spring.config.import added" fail "changes spring.config.import, spring.config.location, or spring.config.additional-location in a configuration file" \
  "" "printf 'spring:\\n  config:\\n    import: optional:classpath:extra.yaml\\n' | put gateway/src/main/resources/application.yaml"
c_location_change "spring.config.additional-location in properties (relaxed binding)" fail "changes spring.config.import, spring.config.location, or spring.config.additional-location" \
  "" "printf 'SPRING.CONFIG.ADDITIONAL_LOCATION=file:/x/\\n' | put gateway/src/main/resources/application-prod.properties"
c_location_change "spring.config.location as a list in config/application.yml" fail "changes spring.config.import, spring.config.location, or spring.config.additional-location" \
  "" "printf 'spring.config.location:\\n  - file:/x/\\n' | put gateway/src/main/resources/config/application.yml"
c_location_change "spring.config.import unchanged, other key changed" pass "" \
  "printf 'spring.config.import: optional:file:.env[.properties]\\nx: 1\\n' | put gateway/src/main/resources/application.yaml" \
  "printf 'spring.config.import: optional:file:.env[.properties]\\nx: 2\\n' | put gateway/src/main/resources/application.yaml"
c_location_change "specs in config/application.yaml of another module" fail "changes gateway.openapi.specs in a configuration file" \
  "" "printf 'gateway.openapi.specs: classpath:x/*.yaml\\n' | put openapi-gateway-core/src/main/resources/config/application.yaml"
c_location_change "specs through a YAML alias" fail "changes gateway.openapi.specs in a configuration file" \
  "" "printf 'x-list: &l [file:/x/*.yaml]\\ngateway:\\n  openapi:\\n    specs: *l\\n' | put gateway/src/main/resources/application.yaml"
c_location_change "specs through a merge key" fail "changes gateway.openapi.specs in a configuration file" \
  "" "printf 'x-base: &b\\n  openapi: {specs: [file:/x/*.yaml]}\\ngateway:\\n  <<: *b\\n' | put gateway/src/main/resources/application.yaml"
c_location_change "YAML file added in the resources of the gateway" fail "changes the configuration file gateway/src/main/resources/extra.yaml in gateway/src/main/resources, whose name does not start with application" \
  "" "printf 'gateway.openapi.specs: file:/x/*.yaml\\n' | put gateway/src/main/resources/extra.yaml"
c_location_change "properties file changed in a subfolder of the resources" fail "changes the configuration file gateway/src/main/resources/config/gateway.properties" \
  "printf 'a=1\\n' | put gateway/src/main/resources/config/gateway.properties" "printf 'a=2\\n' | put gateway/src/main/resources/config/gateway.properties"
c_location_change "YAML file removed from the resources of the gateway" pass "" \
  "printf 'a: 1\\n' | put gateway/src/main/resources/extra.yml" "del gateway/src/main/resources/extra.yml"
c_location_change "other file added in the resources of the gateway" pass "" \
  "" "put gateway/src/main/resources/banner.txt <<< banner"
c_location_change "GatewayOpenApiProperties.java changed" fail "changes gateway/src/main/java/com/johnowl/gateway/routing/GatewayOpenApiProperties.java, the default value of gateway.openapi.specs" \
  "put gateway/src/main/java/com/johnowl/gateway/routing/GatewayOpenApiProperties.java <<< 'record A() {}'" \
  "put gateway/src/main/java/com/johnowl/gateway/routing/GatewayOpenApiProperties.java <<< 'record B() {}'"
c_location_change "GatewayOpenApiProperties.java unchanged" pass "" \
  "put gateway/src/main/java/com/johnowl/gateway/routing/GatewayOpenApiProperties.java <<< 'record A() {}'" "put README.md <<< y"
c_prefix_merge_key

report
