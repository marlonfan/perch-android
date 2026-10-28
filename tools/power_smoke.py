"""Controlled power/network checks. Dedicated emulator only, local server, restore prefs."""
import json
import subprocess
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET
from http.server import ThreadingHTTPServer
from pathlib import Path

import android_smoke as ui
import ntfy_fixture as fixture
from reading_smoke import write_preferences

ROOT = Path(__file__).resolve().parents[1]
requests = []

class Handler(fixture.Handler):
    failure = 0
    retry_after = None

    def do_GET(self):
        requests.append(time.monotonic())
        if self.failure:
            self.send_response(self.failure)
            self.send_header('Content-Length', '0')
            if self.retry_after:
                self.send_header('Retry-After', str(self.retry_after))
            self.end_headers()
            return
        super().do_GET()

    def log_message(self, *args):
        pass

def wait(predicate, timeout=25):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if predicate():
            return
        time.sleep(.25)
    raise AssertionError('Timed out waiting for reception/network transition')

def publish(title):
    started = time.monotonic()
    req = urllib.request.Request('http://127.0.0.1:8788/power-check', data=b'Local reception test.', headers={'Title': title})
    with urllib.request.urlopen(req, timeout=5) as response:
        message = json.load(response)
    return message, started

def received(message):
    return any(m['id'] == message['id'] for m in ui.state()[0]['messages'])

def delivery(label):
    message, started = publish(label)
    wait(lambda: received(message))
    elapsed = time.monotonic() - started
    assert label in ui.adb('shell', 'dumpsys', 'notification', '--noredact'), 'Missing system notification: ' + label
    print(f'PASS {label}: persisted + system notification in {elapsed:.2f}s (ADB observation latency included)', flush=True)
    return message

def cpu_ticks():
    pid = ui.adb('shell', 'pidof', 'app.perch').strip()
    stat = ui.adb('exec-out', 'run-as', 'app.perch', 'cat', f'/proc/{pid}/stat').split()
    return int(stat[13]) + int(stat[14])

def run():
    assert ui.SERIAL == 'emulator-5556', 'Only the isolated PerchReview emulator is allowed'
    data, old = ui.state()
    assert not data.get('url'), 'Refusing to replace a configured server'
    whitelist = ui.adb('shell', 'dumpsys', 'deviceidle', 'whitelist')
    was_exempt = any(line.split(',')[-2:-1] == ['app.perch'] for line in whitelist.splitlines())
    old_saver = ui.adb('shell', 'settings', 'get', 'global', 'low_power').strip() == '1'
    wifi = ui.adb('shell', 'settings', 'get', 'global', 'wifi_on').strip() != '0'
    mobile = ui.adb('shell', 'settings', 'get', 'global', 'mobile_data').strip() != '0'
    server = ThreadingHTTPServer(('127.0.0.1', 8788), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    root = ET.Element('map')
    test = dict(data, url='http://10.0.2.2:8788', enabled=True, appearance='light', topics=[{'name': 'power-check', 'label': '接收测试', 'muted': False, 'joined': 0}], messages=[])
    ET.SubElement(root, 'string', name='data').text = json.dumps(test, ensure_ascii=False)
    ET.SubElement(root, 'string', name='token').text = ''
    ui.adb('shell', 'am', 'force-stop', 'app.perch')
    try:
        write_preferences(ET.tostring(root, encoding='utf-8', xml_declaration=True))
        ui.adb('shell', 'pm', 'grant', 'app.perch', 'android.permission.POST_NOTIFICATIONS')
        ui.adb('shell', 'svc', 'wifi', 'enable')
        ui.adb('shell', 'svc', 'data', 'enable')
        ui.adb('shell', 'input', 'keyevent', '224')
        ui.adb('shell', 'am', 'start', '-n', 'app.perch/.MainActivity')
        wait(lambda: len(requests) > 0)
        time.sleep(1)
        message = delivery('Baseline reception')
        ui.tap_text('收件箱', last=True)
        ui.assert_text('Baseline reception')
        assert not any(n.attrib.get('text') in ('收件箱', '已连接') for n in ui.nodes())
        ui.screenshot('21-connected-inbox')
        count = len(requests)
        ui.tap_text('Baseline reception')
        ui.tap_text('返回')
        # Configuration sync must reuse a live socket, not reconnect on activity recreation.
        ui.adb('shell', 'am', 'start', '-n', 'app.perch/.MainActivity', '-f', '0x10008000')
        time.sleep(2)
        assert len(requests) == count, 'Reading or service sync reopened the stream'
        print('PASS read-state updates + repeated service sync reuse the existing connection', flush=True)
        ui.tap_text('设置', last=True)
        ui.tap_text('锁屏接收')
        ui.assert_text('去设置')
        ui.screenshot('22-lockscreen-settings')
        ui.tap_text('取消')
        ui.adb('shell', 'input', 'keyevent', '3')
        ui.adb('shell', 'dumpsys', 'battery', 'unplug')
        ui.adb('shell', 'cmd', 'power', 'set-mode', '1')
        assert ui.adb('shell', 'settings', 'get', 'global', 'low_power').strip() == '1'
        ui.adb('shell', 'dumpsys', 'deviceidle', 'whitelist', '+app.perch')
        ui.adb('shell', 'input', 'keyevent', '223')
        time.sleep(2)
        delivery('Screen off + battery saver')
        idle = ui.adb('shell', 'dumpsys', 'deviceidle', 'force-idle', 'deep')
        assert 'deep idle' in idle.lower(), idle
        assert 'mState=IDLE' in ui.adb('shell', 'dumpsys', 'deviceidle')
        delivery('Deep Doze + battery exemption')
        (ROOT / 'logs/quiet-power-state.txt').write_text(ui.adb('shell', 'dumpsys', 'power'), encoding='utf-8')
        count = len(requests)
        ticks = cpu_ticks()
        started = time.monotonic()
        print('Starting 60s screen-off idle observation; fixture keepalive interval is 45s', flush=True)
        time.sleep(60)
        delta = cpu_ticks() - ticks
        assert len(requests) == count, 'Idle stream unexpectedly reconnected'
        print(f'PASS {time.monotonic()-started:.1f}s idle: 0 reconnects, CPU +{delta} scheduler ticks; not a physical battery measurement', flush=True)
        (ROOT / 'logs/quiet-batterystats.txt').write_text(ui.adb('shell', 'dumpsys', 'batterystats', '--charged', 'app.perch'), encoding='utf-8')
        ui.adb('shell', 'dumpsys', 'deviceidle', 'unforce')
        ui.adb('shell', 'input', 'keyevent', '224')
        time.sleep(2)
        ui.adb('shell', 'svc', 'wifi', 'disable')
        ui.adb('shell', 'svc', 'data', 'disable')
        time.sleep(3)
        count = len(requests)
        cached, started = publish('Network recovery catch-up')
        time.sleep(8)
        assert len(requests) == count, 'Offline mode should not poll/retry the server'
        print('PASS offline 8s: 0 stream requests', flush=True)
        ui.adb('shell', 'svc', 'wifi', 'enable')
        ui.adb('shell', 'svc', 'data', 'enable')
        restored = time.monotonic()
        wait(lambda: received(cached))
        print(f'PASS network recovery catch-up: {time.monotonic()-restored:.2f}s after network enabled', flush=True)
        # A credentials error should make one request, then a slow retry instead of a loop.
        Handler.failure = 401
        ui.adb('shell', 'svc', 'wifi', 'disable')
        ui.adb('shell', 'svc', 'data', 'disable')
        time.sleep(2)
        count = len(requests)
        ui.adb('shell', 'svc', 'wifi', 'enable')
        ui.adb('shell', 'svc', 'data', 'enable')
        wait(lambda: len(requests) > count)
        count = len(requests)
        time.sleep(8)
        assert len(requests) == count, 'Authentication error caused rapid retries'
        print('PASS HTTP 401: no rapid reconnect during 8s; 5min backoff covered by JVM test', flush=True)
        print('PASS power/network suite; no external publishing, no user-device changes', flush=True)
    finally:
        ui.adb('shell', 'am', 'force-stop', 'app.perch')
        ui.adb('shell', 'dumpsys', 'deviceidle', 'unforce')
        ui.adb('shell', 'dumpsys', 'deviceidle', 'whitelist', '+app.perch' if was_exempt else '-app.perch')
        ui.adb('shell', 'cmd', 'power', 'set-mode', '1' if old_saver else '0')
        ui.adb('shell', 'dumpsys', 'battery', 'reset')
        ui.adb('shell', 'svc', 'wifi', 'enable' if wifi else 'disable')
        ui.adb('shell', 'svc', 'data', 'enable' if mobile else 'disable')
        ui.adb('shell', 'input', 'keyevent', '224')
        write_preferences(old.encode())
        ui.adb('shell', 'am', 'start', '-n', 'app.perch/.MainActivity')
        server.shutdown()
        server.server_close()
        print('Restored demo preferences, network, power mode and exemption on emulator-5556', flush=True)

if __name__ == '__main__':
    run()
