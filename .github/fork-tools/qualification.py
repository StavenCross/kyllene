#!/usr/bin/env python3
"""Fork-only permission, package hygiene and compatibility-source checks."""
import argparse
import configparser
import copy
import io
import json
from pathlib import Path, PurePosixPath
import re
import zipfile

ALLOWED = {'--share=network', '--share=ipc', '--socket=x11', '--device=dri'}
PROFILES = {'baseline': ALLOWED, 'pulseaudio': ALLOWED | {'--socket=pulseaudio'}}
PRIVATE = {'agents.md', 'internal_docs', 'local-docs', 'local-tests', '.git', '.env',
           'local.properties', 'gradle.properties.private'}
SECRET = re.compile(rb'(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{60,}|'
                    rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\s+[A-Za-z0-9+/]{20})')
MAX_ENTRY = 128 * 1024 * 1024


def allowed_permissions(profile):
    if profile not in PROFILES:
        raise ValueError('Unknown permission profile: ' + repr(profile))
    return PROFILES[profile]


def permissions(manifest, profile='baseline'):
    unexpected = set(manifest.get('finish-args', [])) - allowed_permissions(profile)
    if unexpected:
        raise ValueError('Unreviewed sandbox permission: ' + repr(sorted(unexpected)))


def metadata_permissions(text, profile='baseline'):
    config = configparser.ConfigParser(interpolation=None)
    config.read_string(text)
    allowed = {'shared': {'network', 'ipc'}, 'sockets': {'x11'}, 'devices': {'dri'}}
    allowed['sockets'] = {p.removeprefix('--socket=') for p in allowed_permissions(profile)
                          if p.startswith('--socket=')}
    for section in config.sections():
        if section in ('Session Bus Policy', 'System Bus Policy', 'Environment'):
            if dict(config[section]):
                raise ValueError('Unreviewed exported permission/environment section: ' + section)
    if config.has_section('Context'):
        for key, value in config['Context'].items():
            if set(filter(None, value.split(';'))) - allowed.get(key, set()):
                raise ValueError('Unreviewed exported permission: ' + key)


def private_path(name):
    parts = [p.lower() for p in PurePosixPath(name).parts]
    return any(p in PRIVATE or p.startswith('.env.') or p.endswith(('.jks', '.keystore', '.p12', '.pfx')) for p in parts)


def check_bytes(data, name):
    if SECRET.search(data):
        raise ValueError('Possible credential in package member: ' + repr(name))


def archive_scan(archive, label, depth=0):
    if depth > 3:
        raise ValueError('Archive nesting exceeds inspection limit: ' + repr(label))
    total = 0
    for entry in archive.infolist():
        name = label + '!' + entry.filename
        if private_path(entry.filename):
            raise ValueError('Package contains private path: ' + repr(name))
        total += entry.file_size
        if entry.file_size > MAX_ENTRY or total > 2 * 1024**3:
            raise ValueError('Archive exceeds inspection limit: ' + repr(label))
        data = archive.read(entry)
        check_bytes(data, name)
        if entry.filename.lower().endswith(('.jar', '.zip')):
            with zipfile.ZipFile(io.BytesIO(data)) as nested:
                archive_scan(nested, name, depth + 1)


def scan(root):
    for path in root.rglob('*'):
        name = str(path.relative_to(root))
        if private_path(name):
            raise ValueError('Package contains private path: ' + repr(name))
        # Do not follow runtime symlinks outside the inspected package.
        if path.is_symlink() or not path.is_file():
            continue
        with path.open('rb') as stream:
            tail = b''
            while block := stream.read(1024 * 1024):
                check_bytes(tail + block, name)
                tail = block[-512:]
        if path.suffix.lower() in ('.jar', '.zip', '.jmod'):
            with zipfile.ZipFile(path) as archive:
                archive_scan(archive, name)
    print('Package hygiene scan passed (known private paths and credential patterns).')


def select(manifest, sha, ref, runtime, profile='baseline'):
    if not re.fullmatch('[0-9a-f]{40}', sha):
        raise ValueError('Expected exact source commit')
    if runtime and not re.fullmatch(r'\d{2}\.\d{2}', runtime):
        raise ValueError('Expected runtime branch YY.MM')
    result = copy.deepcopy(manifest)
    permissions(result, profile)
    source = result['modules'][0]['sources'][0]
    if source['type'] != 'git':
        raise ValueError('Expected pinned Git source')
    source.update(url='https://github.com/StavenCross/kyllene.git', branch=ref, commit=sha)
    if runtime:
        result['runtime-version'] = runtime
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    p = sub.add_parser('select')
    p.add_argument('manifest', type=Path)
    p.add_argument('--sha', required=True)
    p.add_argument('--ref', required=True)
    p.add_argument('--runtime', default='')
    p.add_argument('--permission-profile', choices=PROFILES, default='baseline')
    p = sub.add_parser('scan')
    p.add_argument('build', type=Path)
    p.add_argument('--permission-profile', choices=PROFILES, default='baseline')
    args = parser.parse_args()
    if args.mode == 'select':
        output = select(json.loads(args.manifest.read_text()), args.sha, args.ref, args.runtime, args.permission_profile)
        args.manifest.with_name('fork-ci.json').write_text(json.dumps(output, indent=2) + '\n')
        print('Selected source', args.sha, 'runtime', output['runtime-version'], 'permission profile', args.permission_profile)
    else:
        metadata_permissions((args.build / 'metadata').read_text(), args.permission_profile)
        scan(args.build / 'files')
        scan(args.build / 'export')
