"""Package a signed Gradle release output; tags must match the APK version."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil

root = Path(__file__).resolve().parents[1]
output = root / 'app/build/outputs/apk/release'
metadata = json.loads((output / 'output-metadata.json').read_text())
assert metadata['applicationId'] == 'app.perch'
element = metadata['elements'][0]
version = element['versionName']
assert re.fullmatch(r'[0-9]+[.][0-9]+[.][0-9]+', version), 'Invalid release version'
if os.environ.get('GITHUB_REF_TYPE') == 'tag':
    assert os.environ['GITHUB_REF_NAME'] == 'v' + version, 'Tag does not match APK version'
assert (root / 'release-notes' / (version + '.md')).is_file(), 'Release notes missing'
apk = output / element['outputFile']
assert apk.name != 'app-release-unsigned.apk', 'Release APK must be signed'
destination = root / 'dist'
destination.mkdir(exist_ok=True)
target = destination / ('Perch-' + version + '.apk')
shutil.copy2(apk, target)
checksum = hashlib.sha256(target.read_bytes()).hexdigest()
(destination / 'SHA256SUMS.txt').write_text(checksum + '  ' + target.name + chr(10), encoding='utf-8')
if os.environ.get('GITHUB_OUTPUT'):
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as handle:
        handle.write('version=' + version + chr(10))
print('Prepared ' + target.name + ' and SHA256SUMS.txt')
