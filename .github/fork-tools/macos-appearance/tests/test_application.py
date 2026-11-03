import importlib.util
from pathlib import Path
import tempfile
import json
import subprocess
import unittest
from unittest.mock import patch
import sys

class ApplicationRunner(unittest.TestCase):
    def test_configuration_uses_packaged_main_class_and_paths(self):
        spec=importlib.util.spec_from_file_location('application', '.github/fork-tools/macos-appearance/application.py')
        module=importlib.util.module_from_spec(spec)
        with patch.object(sys,'platform','darwin'):
            spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as d:
            cfg=Path(d)/'Contents/app/Kyllene.cfg';cfg.parent.mkdir(parents=True)
            cfg.write_text('[Application]\napp.mainclass=dev.hermeskotlin.desktop.MainKt\napp.classpath=$APPDIR/main.jar:$APPDIR/lib.jar\n[JavaOptions]\njava-options=-Dapple.awt.application.appearance=system\n')
            main,classpath,options=module.application_config(cfg)
            self.assertEqual(main,'dev.hermeskotlin.desktop.MainKt')
            self.assertIn(str(cfg.parent/'main.jar'),classpath)
            self.assertEqual(options,['-Dapple.awt.application.appearance=system'])

    def test_unsupported_platform_precedes_file_access(self):
        with patch.object(sys,'platform','linux'), self.assertRaisesRegex(SystemExit,'requires macOS'):
            import runpy
            runpy.run_path('.github/fork-tools/macos-appearance/application.py')


    def test_killed_observer_still_restores_keyboard_state(self):
        spec=importlib.util.spec_from_file_location('application', '.github/fork-tools/macos-appearance/application.py')
        module=importlib.util.module_from_spec(spec)
        with patch.object(sys,'platform','darwin'): spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); output=root/'app-dark'
            cfg=root/'Contents/app/Kyllene.cfg';cfg.parent.mkdir(parents=True)
            cfg.write_text('[Application]\napp.mainclass=dev.hermeskotlin.desktop.MainKt\napp.classpath=$APPDIR/main.jar\n')
            (root/'input-state').touch()
            original={'selected':'original','sources':{'original':True},'errors':[]}
            actions=[]
            def run(args, **kwargs):
                if args[0].endswith('/jar'): return subprocess.CompletedProcess(args,0,'','')
                action=args[1];actions.append(action)
                if action=='snapshot': Path(args[2]).write_text(json.dumps(original));reply=original
                elif action=='restore':reply={'errors':[],'actual':original}
                else:reply={'errors':[]}
                return subprocess.CompletedProcess(args,0,json.dumps(reply),'')
            class Process:
                returncode=None
                def wait(self,timeout=None):
                    if self.returncode is None:raise subprocess.TimeoutExpired('observer',65)
                    return self.returncode
                def kill(self):self.returncode=-9;actions.append('kill')
            with patch.object(module.subprocess,'run',side_effect=run),patch.object(module.subprocess,'Popen',return_value=Process()):
                result=module.observe(cfg,output,root,root/'libappearance.dylib')
            self.assertEqual(actions,['snapshot','prepare','kill','restore'])
            self.assertTrue(result['inputRestored'])
            self.assertIn('65 seconds',result['error'])

if __name__=='__main__':unittest.main()
