#!/usr/bin/env python3
"""Import a local export into an explicitly chosen debug app's PRIVATE files, never APK assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import uuid

NAMES = ('left', 'right', 'front', 'rear')


def validate(source):
    manifest_file = source / 'manifest.json'
    if manifest_file.stat().st_size > 65536:
        raise ValueError('Manifest exceeds bound')
    manifest = json.loads(manifest_file.read_text())
    if manifest['schema_version'] != 1 or manifest.get('calibration_file') != 'camera_calibdata.bin':
        raise ValueError('Schema 1 and exported vehicle calibration required')
    files = {'manifest.json': (65536, None), 'camera_calibdata.bin': (520, manifest['calibration_sha256'])}
    cameras = manifest['cameras']
    if len(cameras) != 4:
        raise ValueError('Four cameras required')
    for name, camera in zip(NAMES, cameras):
        if (camera['camera'] != name or camera['width'] != 720 or camera['height'] != 480
                or camera['raw_row_stride_bytes'] != 1440 or camera['raw_format'] != 'UYVY422'
                or camera['raw'] != name + '.uyvy' or camera['png'] != name + '.png'):
            raise ValueError('Unexpected camera layout')
        files[name + '.uyvy'] = (691200, camera['raw_sha256'])
        files[name + '.png'] = (4 * 1024 * 1024, camera['png_sha256'])
    checked = {}
    for name, (maximum, expected) in files.items():
        file = source / name
        if file.is_symlink() or not file.is_file() or file.stat().st_size > maximum:
            raise ValueError('Invalid bounded file: ' + name)
        data = file.read_bytes()
        if name.endswith('.uyvy') and len(data) != 691200 or name.endswith('.bin') and len(data) != 520:
            raise ValueError('Invalid exact file size')
        digest = hashlib.sha256(data).hexdigest()
        if expected is not None and digest != expected:
            raise ValueError('Checksum mismatch: ' + name)
        checked[name] = (data, digest)
    return checked


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='One complete local snapshot directory')
    parser.add_argument('--serial', required=True, help='Explicit adb device/emulator serial')
    parser.add_argument('--adb', default=shutil.which('adb') or str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
    parser.add_argument('--package', default='com.drivehub.kamera')
    parser.add_argument('--launch', choices=('birdseye', 'curbside', 'none'), default='birdseye')
    args = parser.parse_args()
    source = args.source.resolve()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', source.name):
        raise ValueError('Capture directory needs a safe export identifier')
    if not re.fullmatch(r'[A-Za-z0-9_.]+', args.package):
        raise ValueError('Invalid app package')
    files = validate(source)
    base = [args.adb, '-s', args.serial]
    user = subprocess.check_output(base + ['shell', 'am', 'get-current-user'], text=True).strip()
    if not user.isdecimal():
        raise RuntimeError('Could not identify foreground Android user')

    def remote(command, data=None, check=True):
        command = shlex.join(['run-as', args.package, '--user', user, 'sh', '-c', command])
        return subprocess.run(base + ['shell', '-T', command], input=data, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=check)

    root = 'files/camera-snapshots'
    target = root + '/' + source.name
    remote('umask 077; mkdir -p ' + shlex.quote(root))
    exists = remote('test -e ' + shlex.quote(target), check=False).returncode == 0
    if exists:
        for name, (_, digest) in files.items():
            data = remote('cat ' + shlex.quote(target + '/' + name)).stdout
            if hashlib.sha256(data).hexdigest() != digest:
                raise FileExistsError('Existing capture differs; refusing to overwrite')
        print('Existing private capture verified; no files overwritten.')
    else:
        staging = root + '/.import-' + uuid.uuid4().hex
        remote('umask 077; mkdir ' + shlex.quote(staging))
        try:
            for name, (data, digest) in files.items():
                path = staging + '/' + name
                remote('umask 077; cat > ' + shlex.quote(path), data)
                stored = remote('cat ' + shlex.quote(path)).stdout
                if hashlib.sha256(stored).hexdigest() != digest:
                    raise IOError('Device copy verification failed')
            remote('test ! -e ' + shlex.quote(target) + ' && mv ' + shlex.quote(staging) + ' ' + shlex.quote(target))
        finally:
            remote('rm -rf -- ' + shlex.quote(staging), check=False)
        print('Imported and verified ten files in private debug app storage; no APK/assets modified.')
    if args.launch != 'none':
        activity = 'BirdseyeDebugActivity' if args.launch == 'birdseye' else 'CurbsideDebugActivity'
        subprocess.run(base + ['shell', 'am', 'start', '--user', user, '-n',
                              args.package + '/.birdseye.' + activity, '--es', 'camera_capture_set', source.name], check=True)


if __name__ == '__main__':
    main()
