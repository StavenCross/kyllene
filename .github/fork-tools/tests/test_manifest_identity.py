from pathlib import Path
import json
import subprocess
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1] / 'qualification.py'
CURRENT = 'app.kyllene.Kyllene.Devel'
LEGACY = 'io.github.Giton22.Kyllene.Devel'


class ManifestIdentityTest(unittest.TestCase):
    def call(self, *args):
        return subprocess.run(['python3', str(TOOL), *map(str, args)], capture_output=True, text=True)

    def manifest(self, root, name, identity=None):
        path = root / 'packaging/flatpak' / (name + '.json')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({'app-id': identity or name, 'runtime-version': '26.08', 'finish-args': ['--share=network'],
            'modules': [{'sources': [{'type': 'git', 'commit': 'old'}, 'extra-sources.json']}]}))
        return path

    def test_current_source_packaging_is_selected_with_accepted_extra_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            current = self.manifest(root, CURRENT)
            self.manifest(root, LEGACY)
            result = self.call('manifest', root)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), str(current))
            selected = self.call('select', current, '--sha', 'a' * 40, '--ref', 'refs/heads/contribution')
            self.assertEqual(selected.returncode, 0, selected.stderr)
            out = json.loads(current.with_name('fork-ci.json').read_text())
            self.assertEqual(out['modules'][0]['sources'][0]['commit'], 'a' * 40)
            self.assertEqual(out['modules'][0]['sources'][1], 'extra-sources.json')

    def test_explicit_legacy_identity_remains_supported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            legacy = self.manifest(root, LEGACY)
            result = self.call('manifest', root)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), str(legacy))

    def test_bad_current_identity_does_not_fall_back_to_legacy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.manifest(root, CURRENT, 'unreviewed.App')
            self.manifest(root, LEGACY)
            self.assertNotEqual(self.call('manifest', root).returncode, 0)

    def test_unknown_manifest_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.manifest(root, 'unreviewed.App')
            self.assertNotEqual(self.call('manifest', root).returncode, 0)

    def test_installed_metadata_uses_only_a_reviewed_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = Path(tmp) / 'metadata'
            for identity in [CURRENT, LEGACY]:
                metadata.write_text('[Application]\nname=' + identity + '\n')
                result = self.call('app-id', metadata)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), identity)
            metadata.write_text('[Application]\nname=unreviewed.App\n')
            self.assertNotEqual(self.call('app-id', metadata).returncode, 0)

    def test_workflow_and_hosted_smoke_use_discovered_identity(self):
        base = TOOL.parents[1]
        workflow = (base / 'workflows/fork-flatpak.yml').read_text()
        self.assertIn('if [ -d source/packaging/flatpak ]; then', workflow)
        self.assertIn('qualification.py manifest package', workflow)
        self.assertIn('qualification.py select "$manifest"', workflow)
        smoke = TOOL.with_name('install-smoke.sh').read_text()
        self.assertIn('qualification.py" app-id "$build/metadata"', smoke)
        self.assertLess(smoke.index('RUNNER_ENVIRONMENT'), smoke.index('flatpak info'))
