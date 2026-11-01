"""Download public dependency archives and verify pinned SHA-256 before extraction."""
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import subprocess

root = Path(__file__).resolve().parent
lock = json.loads((root / 'dependency-lock.json').read_text())
deps = root / 'deps'
deps.mkdir(exist_ok=True)
for name in ('mbedtls', 'openssl', 'cjson'):
    item = lock[name]
    url = item['repository'].replace('https://github.com/', 'https://codeload.github.com/') + '/tar.gz/refs/tags/' + item['tag']
    archive = deps / (name + '.tar.gz')
    if not archive.exists():
        subprocess.run(['curl', '-fL', '--retry', '2', '--max-time', '240', url, '-o', str(archive.with_suffix('.download'))], check=True)
        archive.with_suffix('.download').replace(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != item['archive_sha256']:
        raise SystemExit(name + ': archive SHA-256 mismatch; refusing to extract')
    destination = deps / name
    marker = destination / '.verified-archive-sha256'
    if destination.exists():
        if not marker.exists() or marker.read_text().strip() != item['archive_sha256']:
            raise SystemExit(name + ': existing source provenance unknown; move the source directory aside and retry')
        continue
    with tempfile.TemporaryDirectory(dir=deps) as temporary:
        with tarfile.open(archive) as tf:
            tf.extractall(temporary, filter='data')
        entries = list(Path(temporary).iterdir())
        if len(entries) != 1 or not entries[0].is_dir():
            raise SystemExit(name + ': unexpected archive layout')
        entries[0].rename(destination)
        marker.write_text(item['archive_sha256'] + '\n')
    print(name + ': verified and extracted')
