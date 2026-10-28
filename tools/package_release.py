"""Package a reviewable debug APK and source snapshot; no publishing."""
import hashlib
import re
import shutil
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
out = root / 'artifacts'
out.mkdir(exist_ok=True)
version = re.search(r'versionName = "([^"]+)"', (root / 'app/build.gradle.kts').read_text()).group(1)
apk = out / f'Perch-{version}-debug.apk'
shutil.copy2(root / 'app/build/outputs/apk/debug/app-debug.apk', apk)
sources = out / f'Perch-{version}-source.zip'
with zipfile.ZipFile(sources, 'w', zipfile.ZIP_DEFLATED) as archive:
    for folder in ('app/src', 'gradle', 'tools'):
        for path in (root / folder).rglob('*'):
            if path.is_file() and '__pycache__' not in path.parts:
                archive.write(path, path.relative_to(root).as_posix())
    for name in ('settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', '.gitignore', 'app/build.gradle.kts', 'README.md', 'VERIFICATION.md'):
        archive.write(root / name, name)
    for path in out.glob('*.png'):
        if path.name.startswith('quiet-'):
            archive.write(path, path.relative_to(root).as_posix())
    for name in ('quiet-build-final.log', 'quiet-preview.log', 'quiet-reading.log', 'quiet-settings.log', 'quiet-signature.log', 'quiet-previous-signature.log', 'quiet-power.log', 'quiet-batterystats.txt', 'quiet-power-state.txt'):
        path = root / 'logs' / name
        if path.exists():
            archive.write(path, 'logs/' + name)
hashes = '\n'.join(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name for path in (apk, sources)) + '\n'
(out / f'SHA256SUMS-{version}.txt').write_text(hashes, encoding='utf-8')
print('Packaged APK and source in artifacts/', flush=True)
