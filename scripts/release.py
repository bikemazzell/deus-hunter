#!/usr/bin/env python3
"""Trigger a signed GitHub release, or calculate its Android version code."""
import argparse
import re
import subprocess
from pathlib import Path


def version_code(version):
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version):
        raise ValueError('Use a version such as 0.1.0, without a v prefix or suffix.')
    major, minor, patch = map(int, version.split('.'))
    code = major * 1_000_000 + minor * 1_000 + patch
    if minor > 999 or patch > 999 or not 0 < code <= 2_100_000_000:
        raise ValueError('Version components exceed Android version-code limits.')
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version')
    parser.add_argument('--version-code', action='store_true')
    args = parser.parse_args()
    try:
        code = version_code(args.version)
    except ValueError as error:
        parser.error(str(error))
    if args.version_code:
        print(code)
        return
    subprocess.run(
        ['gh', 'workflow', 'run', 'release.yml', '--repo', 'bikemazzell/deus-hunter',
         '--ref', 'master', '-f', f'version={args.version}'],
        cwd=Path(__file__).resolve().parents[1], check=True)
    print(f'Requested release v{args.version}. Track it at:')
    print('https://github.com/bikemazzell/deus-hunter/actions/workflows/release.yml')


if __name__ == '__main__':
    main()
