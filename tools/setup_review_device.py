"""Create an isolated, disposable AVD without touching the user's running device."""
from pathlib import Path

root = Path(__file__).resolve().parents[1] / '.review-avd'
device = root / 'PerchReview.avd'
device.mkdir(parents=True, exist_ok=True)
original = Path('C:/Users/marlo/.android/avd/Medium_Phone.avd/config.ini')
settings = {key.strip(): value.strip() for key, value in (line.split('=', 1) for line in original.read_text().splitlines() if '=' in line)}
config = {
    'AvdId': 'PerchReview',
    'avd.ini.displayname': 'Perch Review',
    'avd.ini.encoding': 'UTF-8',
    'abi.type': 'x86_64',
    'hw.cpu.arch': 'x86_64',
    'hw.cpu.ncore': '2',
    'hw.ramSize': '2048',
    'hw.lcd.width': '1080',
    'hw.lcd.height': '2400',
    'hw.lcd.density': '420',
    'hw.keyboard': 'yes',
    'hw.mainKeys': 'no',
    'hw.gpu.enabled': 'yes',
    'hw.gpu.mode': 'auto',
    'disk.dataPartition.size': '2G',
    'tag.id': settings.get('tag.id', 'google_apis_playstore'),
    'tag.display': 'Google Play',
    'image.sysdir.1': settings['image.sysdir.1'],
    'PlayStore.enabled': 'true',
    'showDeviceFrame': 'no',
}
(device / 'config.ini').write_text(chr(10).join(k + '=' + v for k, v in config.items()) + chr(10))
(root / 'PerchReview.ini').write_text('avd.ini.encoding=UTF-8' + chr(10) + 'path=' + device.as_posix() + chr(10) + 'target=android-35' + chr(10))
print(root.as_posix())
