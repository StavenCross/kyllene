# Personal fork automation

These files belong only to StavenCross/kyllene. They are not upstream contributions.

Fork upstream sync checks Giton22/kyllene master at 00:17, 06:17, 12:17 and 18:17
UTC, and supports manual runs. GitHub may delay schedules or disable them after
60 days without repository activity. A no-change run does not rebuild the app.
The manual validate option runs application checks even without upstream changes.

The policy allows master to differ only under `.github/fork-tools/` and
`.github/workflows/fork-*`. It tests a normal merge locally, then uses GitHub's
fork-sync API and verifies the resulting tree. Conflicts, unexpected app changes,
concurrent updates or insufficient token permissions fail visibly. It never
force-pushes, resets feature branches, changes releases or installs applications.
The API update is not an atomic compare-and-swap: a concurrent update can cause
a reported failure after synchronization has occurred; inspect the remote before retrying.
A separate read-only job tests the resulting SHA; a failed test does not revert
upstream synchronization. Checks run in the same workflow because updates made
with GITHUB_TOKEN do not normally trigger another push workflow.

Start contributions from Giton22 master or a documented clean feature parent,
never fork master. Open a PR in this fork to run CI before proposing the tested
head upstream. Do not merge feature PRs into fork master. After an upstream
squash merge, move only the child's own commits onto current upstream.

Fork contribution isolation runs trusted master policy on Git history only; it
never executes PR code. It rejects fork-only files even if later deleted. It
runs on fork PRs and manually for a named branch. It cannot prevent a human from
bypassing this process and opening an upstream PR directly.

Local checks (requires Python/PyYAML, JBR 25 and Android SDK per CONTRIBUTING):

```sh
python3 .github/fork-tools/checks.py --repo /path/to/contribution-worktree
python3 .github/fork-tools/policy.py contribution --repo /path/to/contribution-worktree --upstream origin/master --head HEAD
python3 -m unittest discover -s .github/fork-tools/tests -v
```

Run these scripts from the maintenance checkout, not by copying them into a
feature branch. The check runner reads upstream CI's actual build/lint/JNI
commands and adds desktop compilation and available packaging contracts. It does
not substitute for the Linux workflow's offline package build or Bazzite's real
GUI, portal, hardware and voice acceptance. Keep those results tied to exact SHAs.


## Flatpak qualification (fork only)

`Fork Flatpak qualification` runs weekly on Monday at 08:43 UTC and manually.
Manual runs select a public branch in this fork and optionally an available
Freedesktop runtime branch. The normal run rebuilds current master with the
latest installed updates for the manifest's runtime/SDK, keeping application
build dependencies locked. It does not automatically upgrade Gradle dependencies.
Until master contains packaging, it uses the published PR01 packaging branch;
source SHA, packaging SHA and runtime commit are recorded separately in the run.
The hosted runner installs native `flatpak-builder` from Ubuntu packages. The
sandboxed Builder uses a desktop host-command service that is absent on a clean
headless runner; changing its XDG paths does not remove that requirement.
Packaging font fixtures use the selected SDK fontconfig through a narrow `fc-list`
wrapper; older host fontconfig versions have different filtering behavior. The
wrapper grants only the synthetic fixture and read-only test checkout paths.
A candidate runtime must actually exist on Flathub; an unavailable branch fails.

The workflow checks the manifest and exported permissions against the reviewed
network/IPC/X11/DRI baseline. New capabilities need an explicit fork-policy change
alongside their feature review. Manual runs may explicitly select the reviewed
`pulseaudio` permission profile, which permits only the additional PulseAudio
socket (microphone and audio output). The default and scheduled profile remains
`baseline`; it still rejects that socket. Selection and exported-metadata checks
use the same explicit profile, recorded in the run summary. Neither profile permits
unrelated filesystem, bus, environment, device or socket widening.
It scans exported files and ZIP/JAR/JMOD contents
for internal-document/config paths and known credential patterns. This is a
bounded leak detector, not proof that arbitrary personal information is absent.
No build bundle, profile or unfiltered log is uploaded as an Actions artifact.

On a disposable GitHub-hosted runner only, it installs the actual app, runs its
bundled JVM, writes a synthetic configuration sentinel from inside the sandbox,
exports a distinct OSTree commit of the same payload and updates the installation.
It verifies the commit changed and sandbox data survived. This tests the package
upgrade mechanism, not application settings/schema migration between versions.
It does not launch the GUI or contact a gateway. Bazzite GUI, document portal and
physical audio acceptance remain separate. The smoke script refuses local runners
or an existing installation. There is no personal-PC self-hosted runner.

This workflow intentionally lives only in the fork's reserved namespace; use its
manual branch input before submitting a feature upstream. It executes candidate
build code only in an ephemeral read-only job, without repository secrets or saved
checkout credentials. Fork PR isolation remains a separate trusted-data-only job.
