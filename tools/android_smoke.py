"""UI + stream integration smoke test on an explicitly selected emulator."""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = os.environ.get('PERCH_ADB', 'D:/adb/adb.exe')
SERIAL = os.environ.get('PERCH_DEVICE', 'emulator-5554')
ROOT = Path(__file__).resolve().parents[1]
SHOTS = ROOT / 'artifacts'
SHOTS.mkdir(exist_ok=True)

def adb(*args, binary=False):
    result = subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, check=True, timeout=35)
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')

def nodes():
    for attempt in range(3):
        adb('shell', 'uiautomator', 'dump', '/sdcard/perch-ui.xml')
        raw = adb('exec-out', 'cat', '/sdcard/perch-ui.xml')
        try:
            return list(ET.fromstring(raw).iter('node'))
        except ET.ParseError:
            time.sleep(.5)
    raise AssertionError('UI hierarchy unavailable')

def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    return str((x1 + x2) // 2), str((y1 + y2) // 2)

def tap_text(text, last=False, contains=False):
    found = []
    for attempt in range(5):
        found = [n for n in nodes() if any((text in n.attrib.get(key, '') if contains else text == n.attrib.get(key)) for key in ('text', 'content-desc'))]
        if found:
            break
        time.sleep(.5)
    if not found:
        raise AssertionError('Missing UI: ' + text)
    adb('shell', 'input', 'tap', *center(found[-1] if last else found[0]))
    time.sleep(.5)

def field(index, text):
    fields = [n for n in nodes() if n.attrib.get('class') == 'android.widget.EditText']
    adb('shell', 'input', 'tap', *center(fields[index]))
    adb('shell', 'input', 'keycombination', '113', '29')
    adb('shell', 'input', 'text', text)

def screenshot(name):
    time.sleep(.5)
    name = os.environ.get('PERCH_SHOT_PREFIX', '') + name
    (SHOTS / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

def assert_text(text):
    for attempt in range(5):
        if any(text in n.attrib.get('text', '') or text in n.attrib.get('content-desc', '') for n in nodes()):
            return
        time.sleep(.5)
    raise AssertionError(text)

def state():
    raw = adb('exec-out', 'run-as', 'app.perch', 'cat', 'shared_prefs/perch.xml')
    root = ET.fromstring(raw)
    data = next(n.text for n in root if n.attrib.get('name') == 'data')
    return json.loads(data), raw

def publish(title, body, priority=3):
    req = urllib.request.Request('http://127.0.0.1:8787/perch-smoke', data=body.encode(), headers={'Title': title, 'Markdown': 'yes', 'Priority': str(priority), 'Authorization': 'Bearer perch-test-token'})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)

def wait_for_message(message_id, timeout=15):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if any(m['id'] == message_id for m in state()[0]['messages']):
            return
        time.sleep(.5)
    raise AssertionError('Message not received: ' + message_id)

def preview():
    # A new install has no preference file yet; inspect the visible demo banner.
    assert_text('连接 ntfy')
    assert not any(n.attrib.get('text') in ('收件箱', '订阅', '设置') for n in nodes()), 'Navigation should use accessible icons without visible labels'
    screenshot('01-inbox-light')
    tap_text('新的想法，已经准备就绪。')
    assert_text('项目进展')
    screenshot('02-detail-light')
    adb('shell', 'input', 'swipe', '550', '1900', '550', '650', '400')
    screenshot('03-markdown-light')
    adb('shell', 'input', 'keyevent', '4')
    tap_text('订阅', last=True)
    assert_text('连接你的 ntfy')
    screenshot('04-subscriptions-light')
    tap_text('设置', last=True)
    assert_text('后台接收')
    screenshot('05-settings-light')
    tap_text('外观')
    tap_text('深色')
    tap_text('收件箱', last=True)
    screenshot('06-inbox-dark')
    tap_text('新的想法，已经准备就绪。')
    screenshot('07-detail-dark')
    adb('shell', 'input', 'swipe', '550', '1900', '550', '650', '400')
    screenshot('07b-markdown-dark')
    adb('shell', 'input', 'keyevent', '4')
    tap_text('设置', last=True)
    tap_text('外观')
    tap_text('浅色')
    tap_text('收件箱', last=True)
    tap_text('新的想法，已经准备就绪。')
    adb('shell', 'input', 'swipe', '550', '1900', '550', '650', '400')
    tap_text('复制代码')
    assert_text('代码已复制')
    tap_text('返回')
    tap_text('设置', last=True)
    print('PASS demo UI: compact headers, native Markdown blocks, code copy, subscriptions, settings, dark mode', flush=True)

def integration():
    tap_text('收件箱', last=True)
    screenshot('01-inbox-light')
    tap_text('设置', last=True)
    tap_text('消息服务')
    field(0, 'http://10.0.2.2:8787')
    field(1, 'perch-test-token')
    tap_text('保存并连接')
    field(0, 'perch-smoke')
    field(1, 'Integration')
    tap_text('订阅', last=True)
    time.sleep(1)
    # The system permission prompt is intentionally exercised, rather than pre-granted.
    permission_nodes = nodes()
    allow = [n for n in permission_nodes if n.attrib.get('resource-id') == 'com.android.permissioncontroller:id/permission_allow_button']
    if allow:
        adb('shell', 'input', 'tap', *center(allow[0]))
    time.sleep(2)
    tap_text('设置', last=True)
    assert_text('已连接')
    data, raw = state()
    assert 'perch-test-token' not in raw, 'Token must not be stored in plaintext'
    print('PASS UI setup + token encryption + live stream', flush=True)
    message = publish('Background delivery', '## Markdown works\n\n- **One**\n- Two\n\n> Quietly delivered.\n\n```python\nprint(42)\n```', 4)
    wait_for_message(message['id'])
    tap_text('收件箱', last=True)
    assert_text('Background delivery')
    screenshot('08-live-inbox')
    adb('shell', 'input', 'keyevent', '3')
    background = publish('Tap to continue', '**Background notification**\n\nThis arrived while the app was on the home screen.', 4)
    wait_for_message(background['id'])
    notifications = adb('shell', 'dumpsys', 'notification', '--noredact')
    assert 'Tap to continue' in notifications
    adb('shell', 'cmd', 'statusbar', 'expand-notifications')
    time.sleep(1)
    screenshot('09-system-notifications')
    tap_text('Tap to continue')
    assert_text('Background notification')
    screenshot('10-notification-detail')
    data, _ = state()
    assert next(m for m in data['messages'] if m['id'] == background['id'])['read']
    print('PASS background delivery + notification tap opens correct message + read state', flush=True)
    before = len(data['messages'])
    with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8787/__replay', data=b''), timeout=10):
        pass
    time.sleep(2)
    assert len(state()[0]['messages']) == before, 'Replay must not duplicate persisted messages'
    print('PASS repeated event deduplication', flush=True)
    adb('shell', 'am', 'force-stop', 'app.perch')
    offline = publish('Offline catch-up', 'Persisted while disconnected.')
    adb('shell', 'am', 'start', '-n', 'app.perch/.MainActivity')
    wait_for_message(offline['id'])
    assert len(state()[0]['messages']) == before + 1
    print('PASS reconnect catch-up + local persistence after process restart', flush=True)
    tap_text('设置', last=True)
    tap_text('后台接收')
    time.sleep(1)
    assert not state()[0]['enabled']
    adb('shell', 'am', 'force-stop', 'app.perch')
    adb('shell', 'svc', 'wifi', 'disable')
    adb('shell', 'svc', 'data', 'disable')
    adb('shell', 'am', 'start', '-n', 'app.perch/.MainActivity')
    assert_text('Offline catch-up')
    tap_text('Offline catch-up')
    assert_text('Persisted while disconnected.')
    adb('shell', 'svc', 'wifi', 'enable')
    adb('shell', 'svc', 'data', 'enable')
    print('PASS offline reading + pause preference persistence', flush=True)

if __name__ == '__main__':
    try:
        if sys.argv[1:] == ['integration']:
            integration()
        else:
            preview()
    except Exception:
        screenshot('failure')
        raise
