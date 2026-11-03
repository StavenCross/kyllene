import importlib.util
import json
from pathlib import Path
import subprocess
import plistlib
import os
import sys
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('qualification', '.github/fork-tools/macos-appearance/qualify.py')
q=importlib.util.module_from_spec(spec)
with patch.object(sys, 'platform', 'darwin'), patch.object(sys, 'path', [str(Path('.github/fork-tools/macos-appearance').resolve()), *sys.path]):
    spec.loader.exec_module(q)

class ProbeContracts(unittest.TestCase):
    def test_native_json_survives_warning_on_stderr(self):
        value=q.run(sys.executable, '-c', 'import sys; print("warning", file=sys.stderr); print(\'{"application":"dark"}\')')
        self.assertEqual(json.loads(value)['application'], 'dark')

    @unittest.skipIf(sys.platform == 'win32', 'The Mac capture observer uses POSIX pipe readiness')
    def test_optional_screenshot_timeout_is_unavailable(self):
        with tempfile.TemporaryDirectory() as d:
            def execute(*args, **kwargs):
                raise subprocess.TimeoutExpired(args[0], 5)
            child=[sys.executable, '-c', 'print("PANEL_VISIBLE", flush=True)']
            with patch.object(q.subprocess, 'run', side_effect=execute):
                result=q.screenshot(child, Path(d), Path(d)/'screen.jpg')
            self.assertEqual(result['status'], 'unavailable')


    def orchestration(self, explicit='inherited', restore_failure=False, observer_exit=0, input_restored=True, wrong_view=False):
        with tempfile.TemporaryDirectory() as d:
            source=Path(d)/'source'; output=Path(d)/'report'
            app=source/'desktopApp/build/compose/binaries/main-release/app/Kyllene.app'
            cfg=app/'Contents/app/Kyllene.cfg';cfg.parent.mkdir(parents=True)
            cfg.write_text('[JavaOptions]\njava-options=-Dapple.awt.application.appearance=system\n')
            (app/'Contents/Info.plist').write_bytes(plistlib.dumps({'CFBundleIdentifier':'synthetic'}))
            state={'dark':False,'changes':0}
            def theme(dark):
                state['changes']+=1
                if restore_failure and state['changes']==3: raise RuntimeError('restore failed')
                state['dark']=dark
            def run(*args):
                if args[0]=='osascript':return 'false'
                if Path(str(args[0])).name=='system-appearance':return 'NSAppearanceNameDarkAqua' if state['dark'] else 'NSAppearanceNameAqua'
                if 'AppearanceProbe' in args:
                    palette='NSAppearanceNameDarkAqua' if state['dark'] else 'NSAppearanceNameAqua'
                    return json.dumps({'explicitApplicationAppearance': explicit,'application':palette,'openPanel':palette,'savePanel':palette,'alert':palette,'windows':[{'title':'Kyllene','window':palette,'contentView':palette,'firstResponderAppearance':'NSAppearanceNameAqua' if wrong_view else palette}]})
                return 'synthetic'
            failure=None
            with patch.object(q,'observe',side_effect=lambda *args: {'exitCode':observer_exit,'inputRestored':input_restored,'observations': {'nativeAppearance': run('AppearanceProbe')}}),patch.object(q.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'valid','')),patch.object(q,'run',side_effect=run),patch.object(q,'set_dark',side_effect=theme),patch.object(q,'screenshot',return_value={'status':'unavailable'}),patch.dict(os.environ,{'JAVA_HOME':d}):
                try:q.main(source,'candidate',output)
                except Exception as error:failure=error
            return failure,json.loads((output/'qualification.json').read_text())

    def test_explicit_appearance_is_rejected_even_when_palette_matches(self):
        failure,report=self.orchestration(explicit='NSAppearanceNameDarkAqua')
        self.assertIsInstance(failure,ValueError)
        self.assertEqual(report['status'],'failed')

    def test_restoration_failure_cannot_report_pass(self):
        failure,report=self.orchestration(restore_failure=True)
        self.assertIsInstance(failure,RuntimeError)
        self.assertEqual(report['status'],'failed')


    def test_nonzero_application_exit_cannot_qualify(self):
        failure,report=self.orchestration(observer_exit=9)
        self.assertIsInstance(failure,ValueError)
        self.assertEqual(report['status'],'failed')

    def test_missing_input_restoration_cannot_qualify(self):
        failure,report=self.orchestration(input_restored=False)
        self.assertIsInstance(failure,ValueError)
        self.assertEqual(report['status'],'failed')

    def test_light_native_input_view_under_dark_system_cannot_qualify(self):
        failure,report=self.orchestration(wrong_view=True)
        self.assertIsInstance(failure,ValueError)
        self.assertEqual(report['status'],'failed')

if __name__=='__main__':
    unittest.main()
