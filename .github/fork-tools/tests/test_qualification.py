import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('qualification', Path(__file__).resolve().parents[1] / 'qualification.py')
q = importlib.util.module_from_spec(spec)
spec.loader.exec_module(q)


class QualificationTest(unittest.TestCase):
    def test_pulseaudio_requires_an_explicit_profile_without_changing_baseline(self):
        manifest = {'finish-args': sorted(q.ALLOWED | {'--socket=pulseaudio'})}
        metadata = '[Context]\nshared=network;ipc;\nsockets=x11;pulseaudio;\ndevices=dri;\n'
        for check, value in [(q.permissions, manifest), (q.metadata_permissions, metadata)]:
            with self.assertRaisesRegex(ValueError, 'permission'):
                check(value)
            check(value, profile='pulseaudio')
            with self.assertRaisesRegex(ValueError, 'permission'):
                check(value)
        self.assertNotIn('--socket=pulseaudio', q.ALLOWED)

    def test_both_profiles_reject_other_permission_widening(self):
        for profile in ['baseline', 'pulseaudio']:
            for extra in ['--filesystem=home', '--socket=wayland', '--socket=session-bus', '--device=all']:
                with self.subTest(profile=profile, extra=extra), self.assertRaisesRegex(ValueError, 'permission'):
                    q.permissions({'finish-args': [extra]}, profile=profile)
            for metadata in ['[Context]\nfilesystems=host;\n', '[Context]\nsockets=wayland;\n',
                             '[Session Bus Policy]\norg.freedesktop.secrets=talk\n', '[Environment]\nX=1\n']:
                with self.subTest(profile=profile, metadata=metadata), self.assertRaisesRegex(ValueError, 'permission'):
                    q.metadata_permissions(metadata, profile=profile)

    def test_unknown_profile_fails_closed(self):
        for check, value in [(q.permissions, {}), (q.metadata_permissions, '[Context]\n')]:
            with self.assertRaisesRegex(ValueError, 'profile'):
                check(value, profile='unreviewed')

    def test_workflow_profile_is_explicit_for_selection_scan_and_summary(self):
        import yaml
        workflow = yaml.safe_load((Path(__file__).resolve().parents[2] / 'workflows/fork-flatpak.yml').read_text())
        trigger = workflow.get('on', workflow.get(True))
        choice = trigger['workflow_dispatch']['inputs']['permission_profile']
        self.assertEqual(choice['default'], 'baseline')
        self.assertEqual(choice['options'], ['baseline', 'pulseaudio'])
        checked = 0
        for step in workflow['jobs']['qualification']['steps']:
            command = step.get('run', '')
            if 'qualification.py select ' in command or 'qualification.py scan ' in command:
                self.assertEqual(step['env']['PERMISSION_PROFILE'], "${{ inputs.permission_profile || 'baseline' }}")
                self.assertIn('--permission-profile "$PERMISSION_PROFILE"', command)
                checked += 1
            if 'qualification.py select ' in command:
                self.assertIn('Permission profile:', command)
        self.assertEqual(checked, 2)

    def test_permission_increase_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'permission'):
            q.permissions({'finish-args': ['--filesystem=home']})
        q.permissions({'finish-args': sorted(q.ALLOWED)})

    def test_exported_permission_increase_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'permission'):
            q.metadata_permissions('[Context]\nshared=network;ipc;\nfilesystems=host;\n')
        q.metadata_permissions('[Context]\nshared=network;ipc;\nsockets=x11;\ndevices=dri;\n')

    def test_private_paths_and_tokens_are_rejected_without_printing_secret(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'AGENTS.md').write_text('private')
            with self.assertRaisesRegex(ValueError, 'AGENTS.md'):
                q.scan(root)
            (root / 'AGENTS.md').unlink()
            token = 'ghp_' + 'X' * 36
            (root / 'settings.json').write_text(token)
            with self.assertRaises(ValueError) as error:
                q.scan(root)
            self.assertNotIn(token, str(error.exception))

    def test_credential_across_read_boundary_is_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'payload').write_bytes(b' ' * (1024 * 1024 - 10) + b'ghp_' + b'X' * 36)
            with self.assertRaisesRegex(ValueError, 'credential'):
                q.scan(root)

    def test_exported_bus_access_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'permission'):
            q.metadata_permissions('[Session Bus Policy]\norg.freedesktop.secrets=talk\n')

    def test_archive_contents_are_scanned(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with zipfile.ZipFile(root / 'app.jar', 'w') as archive:
                archive.writestr('local-docs/notes.md', 'private')
            with self.assertRaisesRegex(ValueError, 'private path'):
                q.scan(root)

    def test_runtime_selection_preserves_locked_dependencies(self):
        manifest = {'finish-args': list(q.ALLOWED), 'runtime-version': '26.08',
                    'modules': [{'sources': [{'type': 'git', 'commit': 'old'}, {'sha512': 'keep'}]}]}
        out = q.select(manifest, 'a' * 40, 'master', '27.08')
        self.assertEqual(out['modules'][0]['sources'][0]['commit'], 'a' * 40)
        self.assertEqual(out['modules'][0]['sources'][1], {'sha512': 'keep'})
        self.assertEqual(out['runtime-version'], '27.08')
        with self.assertRaises(ValueError):
            q.select(manifest, 'bad', 'master', '../../bad')

    def test_ordinary_package_is_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'LICENSE').write_text('Public license')
            q.scan(root)

    def test_hosted_packaging_discovery_has_pillow_available(self):
        import yaml
        workflow = Path(__file__).resolve().parents[2] / 'workflows/fork-flatpak.yml'
        steps = yaml.safe_load(workflow.read_text())['jobs']['qualification']['steps']
        installed = False
        for step in steps:
            command = step.get('run', '')
            if 'apt-get install' in command and 'python3-pil' in command:
                installed = True
            if 'unittest discover -s packaging/flatpak/tests' in command:
                self.assertTrue(installed, 'Linux packaging discovery requires Pillow first')
                return
        self.fail('packaging discovery step missing')
