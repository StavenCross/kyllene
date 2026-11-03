#!/usr/bin/env python3
"""Launch actual packaged Kyllene classes with an isolated profile and an in-process UI observer."""
import sys

if sys.platform != 'darwin':
    sys.exit('This application appearance test requires macOS.')

import json
import os
from pathlib import Path
import subprocess


def application_config(cfg):
    sections, section = {}, ''
    for line in cfg.read_text().splitlines():
        line = line.strip()
        if line.startswith('[') and line.endswith(']'):
            section = line
        elif '=' in line:
            key, value = line.split('=', 1)
            sections.setdefault((section, key), []).append(value)
    main = sections.get(('[Application]', 'app.mainclass'), [])
    classpath = sections.get(('[Application]', 'app.classpath'), [])
    if len(main) != 1 or not classpath:
        raise ValueError('Expected one packaged main class and a classpath')
    def expand(value):
        return value.replace('${APPDIR}', str(cfg.parent)).replace('$APPDIR', str(cfg.parent))
    return main[0], ':'.join(expand(p) for p in classpath), [expand(p) for p in sections.get(('[JavaOptions]', 'java-options'), [])]


def observe(cfg, output, jdk, library):
    output.mkdir(parents=True, exist_ok=True)
    main, classpath, options = application_config(cfg)
    manifest = output / 'MANIFEST.MF'
    manifest.write_text('Premain-Class: ApplicationProbe\n\n')
    agent = output / 'application-observer.jar'
    subprocess.run([str(jdk / 'bin/jar'), 'cfm', str(agent), str(manifest), '-C', str(output.parent), 'ApplicationProbe.class', '-C', str(output.parent), 'InputSource.class', '-C', str(output.parent), 'AppearanceProbe.class'], check=True, timeout=30)
    profile = output / 'fresh-profile'
    profile.mkdir()
    theme = 'Dark' if output.name.endswith('dark') else 'Light'
    settings = profile / 'Library/Application Support/Herald/settings.json'
    settings.parent.mkdir(parents=True)
    settings.write_text(json.dumps({'settings.v1': json.dumps({'theme': theme})}))
    command = [str(jdk / 'bin/java'), *options, '-Duser.home=' + str(profile), '-javaagent:' + str(agent) + '=' + str(library) + '|' + str(output), '-cp', classpath, main]
    result = {'appTheme': theme, 'scope': 'Actual packaged Kyllene classes, generated launcher options, full packaging SDK, isolated profile; not trimmed-runtime acceptance'}
    tool = output.parent / 'input-state'
    snapshot = output / 'keyboard-original.json'
    def state(action):
        command = subprocess.run([str(tool), action, str(snapshot)], capture_output=True, text=True, timeout=20)
        value = json.loads(command.stdout)
        if command.returncode != 0 or value.get('errors') != []:
            raise ValueError(f'Input state {action} failed: {value}')
        return value
    mutated = False
    try:
        original = state('snapshot')
        mutated = True  # Preparation may change only part of the state before failing.
        result['inputPreparation'] = state('prepare')
        with (output / 'application.log').open('w') as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            try:
                process.wait(timeout=65)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                result['error'] = 'Application observer exceeded 65 seconds'
        result['exitCode'] = process.returncode
        report = output / 'application-report.txt'
        if report.exists():
            result['observations'] = dict(line.split('=', 1) for line in report.read_text().splitlines() if '=' in line)
        else:
            result['error'] = result.get('error', 'No application observer report; see runner log')
            print((output / 'application.log').read_text()[-6000:], file=sys.stderr)
    except Exception as error:
        result['error'] = str(error)
    finally:
        if mutated:
            try:
                restored = state('restore')
                if restored.get('actual', {}).get('selected') != original.get('selected') or restored.get('actual', {}).get('sources') != original.get('sources'):
                    raise ValueError('Restored input state differs from persisted original')
                result['inputRestoration'] = restored
                result['inputRestored'] = True
            except Exception as error:
                result['inputRestored'] = False
                result['restorationError'] = str(error)
    (output / 'application.json').write_text(json.dumps(result, indent=2) + '\n')
    return result
