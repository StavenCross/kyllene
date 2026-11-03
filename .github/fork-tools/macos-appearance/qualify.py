#!/usr/bin/env python3
"""Disposable Mac runner qualification; no personal profiles, gateways or signing credentials."""
import sys

if sys.platform != 'darwin':
    sys.exit('This native appearance qualification requires macOS.')

import json
from application import observe
import os
from pathlib import Path
import plistlib
import select
import subprocess
import time


def run(*args):
    return subprocess.check_output(args, text=True, timeout=120).strip()


def launcher_options(cfg):
    section, options = '', []
    for line in cfg.read_text().splitlines():
        line = line.strip()
        if line.startswith('[') and line.endswith(']'):
            section = line
        if section == '[JavaOptions]' and line.startswith('java-options='):
            options.append(line.removeprefix('java-options='))
    return options


def set_dark(dark):
    run('osascript', '-e', 'tell application "System Events" to tell appearance preferences to set dark mode to ' + str(dark).lower())


def screenshot(command, folder, destination):
    process = subprocess.Popen([*command, str(folder)], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        deadline = time.monotonic() + 6
        while time.monotonic() < deadline:
            if not select.select([process.stdout], [], [], 0.2)[0]:
                continue
            line = process.stdout.readline().strip()
            if line == 'PANEL_VISIBLE':
                try:
                    capture = subprocess.run(['screencapture', '-x', '-t', 'jpg', str(destination)], capture_output=True, text=True, timeout=5)
                except (OSError, subprocess.TimeoutExpired) as error:
                    return {'status': 'unavailable', 'reason': str(error)}
                if capture.returncode or not destination.is_file() or destination.stat().st_size == 0:
                    return {'status': 'unavailable', 'reason': capture.stderr.strip() or 'No image captured'}
                return {'status': 'captured-unreviewed', 'file': destination.name, 'scope': 'AWT/FileKit-style native panel harness, not Kyllene UI'}
            if process.poll() is not None:
                break
        return {'status': 'unavailable', 'reason': 'No visible panel confirmation within six seconds'}
    finally:
        try:
            process.communicate(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate()


def main(source, variant, output):
    if variant not in ('baseline', 'candidate'):
        raise ValueError('Expected baseline or candidate')
    source, output = Path(source).resolve(), Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    tools = Path(__file__).resolve().parent
    report = {'variant': variant, 'status': 'failed', 'observations': []}
    previous = None
    try:
        report['source'] = run('git', '-C', str(source), 'rev-parse', 'HEAD')
        report['os'] = run('sw_vers')
        report['architecture'] = run('uname', '-m')
        jdk = Path(os.environ['JAVA_HOME'])
        report['java'] = run(str(jdk / 'bin/java'), '--version')
        report['probeRuntime'] = 'Same JBR SDK used for packaging; full SDK, not jlink-trimmed app runtime'
        configs = list(source.glob('desktopApp/build/compose/binaries/main-release/app/*.app/Contents/app/*.cfg'))
        if len(configs) != 1:
            raise ValueError(f'Expected one packaged launcher configuration, found {len(configs)}')
        cfg = configs[0]
        appdir = cfg.parent
        app = appdir.parent.parent
        report['bundle'] = plistlib.loads((app / 'Contents/Info.plist').read_bytes()).get('CFBundleIdentifier')
        options = launcher_options(cfg)
        appearance = [v for v in options if v.partition('=')[0] == '-Dapple.awt.application.appearance']
        report['appearanceOptions'] = appearance
        expected = [] if variant == 'baseline' else ['-Dapple.awt.application.appearance=system']
        if appearance != expected:
            raise ValueError(f'Unexpected launcher appearance options: {appearance}')
        checker = Path(os.environ.get('LAUNCHER_CHECK', source / 'desktopApp/tests/check_macos_launcher.py')).resolve()
        checked = subprocess.run(['python3', str(checker), str(cfg)], text=True, capture_output=True, timeout=20)
        report['launcherCheck'] = {'exitCode': checked.returncode, 'message': checked.stdout + checked.stderr}
        expected_exit = 1 if variant == 'baseline' else 0
        if checked.returncode != expected_exit or (variant == 'baseline' and 'found []' not in checked.stderr):
            raise ValueError(f'Unexpected launcher regression result: {report["launcherCheck"]}')
        run(str(jdk / 'bin/javac'), '-d', str(output), *[str(tools / name) for name in ('AppearanceProbe.java', 'InputSource.java', 'ApplicationProbe.java')])
        library = output / 'libappearance.dylib'
        run('clang', '-shared', '-fPIC', '-fobjc-arc', '-framework', 'AppKit', '-framework', 'Carbon', '-framework', 'ApplicationServices', '-I' + str(jdk / 'include'), '-I' + str(jdk / 'include/darwin'), str(tools / 'appearance.m'), str(tools / 'input-source.m'), '-o', str(library))
        state_tool = output / 'input-state'
        run('clang', '-fobjc-arc', '-framework', 'AppKit', '-framework', 'Carbon', '-framework', 'ApplicationServices', '-I' + str(jdk / 'include'), '-I' + str(jdk / 'include/darwin'), str(tools / 'input-source.m'), str(tools / 'input-state.m'), '-o', str(state_tool))
        swift = output / 'system-appearance'
        run('swiftc', str(tools / 'system-appearance.swift'), '-o', str(swift))
        old = run('osascript', '-e', 'tell application "System Events" to tell appearance preferences to get dark mode')
        if old not in ('true', 'false'):
            raise ValueError('Cannot determine original runner theme')
        previous = old == 'true'
        folder = output / 'synthetic-files'
        folder.mkdir(exist_ok=True)
        (folder / 'example.txt').write_text('Synthetic appearance test data only.\n')
        expanded = [v.replace('$APPDIR', str(appdir)) for v in options]
        command = [str(jdk / 'bin/java'), *expanded, '-cp', str(output), 'AppearanceProbe', str(library)]
        for dark in (True, False):
            set_dark(dark)
            desired = 'NSAppearanceNameDarkAqua' if dark else 'NSAppearanceNameAqua'
            observed = None
            for _ in range(10):
                observed = run(str(swift))
                if observed == desired:
                    break
                time.sleep(0.5)
            if observed != desired:
                raise ValueError(f'Runner did not enter requested system theme: {desired}, observed {observed}')
            result = json.loads(run(*command))
            record = {'system': observed, 'native': result}
            report['observations'].append(record)
            explicit = 'inherited' if variant == 'candidate' else 'NSAppearanceNameAqua'
            if result['explicitApplicationAppearance'] != explicit:
                raise ValueError(f'Expected application appearance {explicit}, observed {result}')
            target = desired if variant == 'candidate' else 'NSAppearanceNameAqua'
            for key in ('application', 'openPanel', 'savePanel', 'alert'):
                if result[key] != target:
                    raise ValueError(f'{key}: expected {target}, observed {result[key]}')
            record['screenshot'] = screenshot(command, folder, output / ('dark.jpg' if dark else 'light.jpg'))
            actual = observe(cfg, output / ('app-dark' if dark else 'app-light'), jdk, library)
            record['applicationProbe'] = actual
            observations = actual.get('observations', {})
            if actual.get('exitCode') != 0 or actual.get('inputRestored') is not True or 'error' in actual or 'error' in observations:
                raise ValueError(f'Actual Kyllene observer failed: {actual}')
            native = json.loads(observations['nativeAppearance'])
            if native['application'] != target or native['explicitApplicationAppearance'] != explicit:
                raise ValueError(f'Actual Kyllene native appearance differs: {native}')
            windows = [w for w in native.get('windows', []) if w['title'] == 'Kyllene']
            if not windows:
                raise ValueError('Actual Kyllene native window was not observed')
            if variant == 'candidate':
                for window in windows:
                    for key in ('window', 'contentView', 'firstResponderAppearance'):
                        if window.get(key) != target:
                            raise ValueError(f'Kyllene {key} appearance differs: {window}')
        report['status'] = 'passed'
        report['notCovered'] = 'No gateway/chat/provider test, trimmed-runtime acceptance, signing or notarization. Input-language indicator requires visual review of actual-app captures.'
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        try:
            if previous is not None:
                set_dark(previous)
        except Exception as error:
            report['status'] = 'failed'
            report['restoreError'] = str(error)
            raise
        finally:
            (output / 'qualification.json').write_text(json.dumps(report, indent=2) + '\n')
            print(json.dumps(report, indent=2))


if __name__ == '__main__':
    if len(sys.argv) != 4:
        sys.exit('Usage: qualify.py SOURCE baseline|candidate OUTPUT')
    main(*sys.argv[1:])
