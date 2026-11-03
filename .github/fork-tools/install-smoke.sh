#!/usr/bin/env bash
# Only on a disposable hosted runner: never replace a developer's installed app.
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || {
  echo 'This installation test requires a disposable GitHub-hosted runner.' >&2; exit 1;
}
build=$(realpath "$1")
repo=$(realpath "$2")
app=$(python3 "$(dirname "$0")/qualification.py" app-id "$build/metadata")
if flatpak info --user "$app" >/dev/null 2>&1; then
  echo 'Refusing to replace an existing installation.' >&2; exit 1
fi
flatpak remote-add --user --no-gpg-verify fork-smoke "file://$repo"
flatpak install --user --noninteractive fork-smoke "$app//devel"
before=$(flatpak info --user --show-commit "$app")
# Expand these variables inside the app sandbox, not on the host.
# shellcheck disable=SC2016
flatpak run --user --command=sh "$app" -ec '
  test -x /app/bin/kyllene
  /app/lib/kyllene/lib/runtime/bin/java -version
  test ! -e "$XDG_CONFIG_HOME/fork-ci-sentinel"
  printf "%s" "synthetic-ci-profile" > "$XDG_CONFIG_HOME/fork-ci-sentinel"
'
# A second OSTree commit of the same payload exercises the package upgrade path.
# It does not claim to test application settings/schema migrations between releases.
flatpak build-export --subject='Fork CI upgrade fixture' "$repo" "$build" devel
flatpak update --user --noninteractive "$app"
after=$(flatpak info --user --show-commit "$app")
test "$before" != "$after"
# Expand these variables inside the app sandbox, not on the host.
# shellcheck disable=SC2016
flatpak run --user --command=sh "$app" -ec '
  test "$(cat "$XDG_CONFIG_HOME/fork-ci-sentinel")" = synthetic-ci-profile
  /app/lib/kyllene/lib/runtime/bin/java -version
'
printf 'Installed %s; upgraded %s -> %s; synthetic sandbox data preserved.\n' "$app" "$before" "$after"
