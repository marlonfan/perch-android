"""Dedicated emulator: deletion UI, persisted tombstones, replay and pause/resume."""
import json
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET
from http.server import ThreadingHTTPServer

import android_smoke as ui
import ntfy_fixture as fixture
from reading_smoke import write_preferences

requests = []
class Handler(fixture.Handler):
    def do_GET(self):
        requests.append(self.path)
        super().do_GET()
    def log_message(self, *args):
        pass

def wait(predicate, timeout=20):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate(): return
        time.sleep(.25)
    raise AssertionError('Timed out waiting for deletion/reception state')

def run():
    assert ui.SERIAL == 'emulator-5556', 'Dedicated review emulator only'
    data, old = ui.state()
    assert not data.get('url'), 'Refusing to replace configured server'
    now = int(time.time())
    for index in range(1,4):
        fixture.events.append(dict(id=f'delete-{index}', event='message', time=now, topic='delete-test', title=f'Delete {index}', message='Local test message', priority=3))
    server = ThreadingHTTPServer(('127.0.0.1',8790),Handler)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    root=ET.Element('map')
    configured=dict(data,url='http://10.0.2.2:8790',enabled=True,topics=[dict(name='delete-test',label='删除测试',joined=0,muted=False)],messages=[],deleted=[])
    ET.SubElement(root,'string',name='data').text=json.dumps(configured,ensure_ascii=False)
    ET.SubElement(root,'string',name='token').text=''
    ui.adb('shell','am','force-stop','app.perch')
    try:
        write_preferences(ET.tostring(root,encoding='utf-8',xml_declaration=True))
        ui.adb('shell','pm','grant','app.perch','android.permission.POST_NOTIFICATIONS')
        ui.adb('shell','am','start','-n','app.perch/.MainActivity','-f','0x10008000')
        wait(lambda: len(ui.state()[0]['messages'])==3)
        ui.tap_text('收件箱',last=True)
        ui.tap_text('Delete 1')
        ui.tap_text('删除消息')
        ui.tap_text('取消')
        assert len(ui.state()[0]['messages'])==3
        ui.tap_text('删除消息')
        ui.tap_text('删除')
        wait(lambda: len(ui.state()[0]['messages'])==2)
        assert any(k['id']=='delete-1' for k in ui.state()[0]['deleted'])
        assert 'delete-1' not in ui.adb('shell','cmd','notification','list')
        print('PASS detail delete + confirmation cancel + system notification removal',flush=True)
        node=next(n for n in ui.nodes() if n.attrib.get('text')=='Delete 2')
        x,y=ui.center(node)
        ui.adb('shell','input','swipe',x,y,x,y,'900')
        ui.assert_text('已选 1 条')
        ui.tap_text('Delete 3')
        ui.assert_text('已选 2 条')
        ui.screenshot('31-batch-selection')
        ui.tap_text('全选消息')
        ui.assert_text('选择消息')
        ui.tap_text('全选消息')
        ui.assert_text('已选 2 条')
        ui.tap_text('删除所选消息')
        ui.screenshot('32-delete-confirmation')
        ui.tap_text('删除')
        wait(lambda: len(ui.state()[0]['messages'])==0)
        assert len(ui.state()[0]['deleted'])==3
        assert ui.state()[0]['topics']==configured['topics']
        print('PASS long-press multi-select + select all + batch delete; subscriptions preserved',flush=True)
        count=len(requests)
        ui.adb('shell','am','force-stop','app.perch')
        ui.adb('shell','am','start','-n','app.perch/.MainActivity')
        wait(lambda: len(requests)>count)
        time.sleep(1)
        assert len(ui.state()[0]['messages'])==0
        assert len(ui.state()[0]['deleted'])==3
        req=urllib.request.Request('http://127.0.0.1:8790/delete-test',data=b'Fresh message',headers={'Title':'New after deletion'})
        with urllib.request.urlopen(req,timeout=5) as response: fresh=json.load(response)
        wait(lambda: len(ui.state()[0]['messages'])==1)
        assert ui.state()[0]['messages'][0]['id']==fresh['id']
        print('PASS restart + cached replay keep deleted messages hidden; fresh messages still arrive',flush=True)
        ui.tap_text('设置',last=True)
        pid=ui.adb('shell','pidof','app.perch').strip()
        for index in range(3):
            ui.tap_text('后台接收')
            wait(lambda: not ui.state()[0]['enabled'])
            time.sleep(.5)
            assert ui.adb('shell','pidof','app.perch').strip()==pid
            ui.tap_text('后台接收')
            wait(lambda: ui.state()[0]['enabled'])
            ui.assert_text('已连接')
            assert ui.adb('shell','pidof','app.perch').strip()==pid
        crash=ui.adb('logcat','-b','crash','-d','--pid',pid)
        (ui.ROOT/'logs/delete-emulator-crash.log').write_text(crash,encoding='utf-8')
        assert 'FATAL EXCEPTION' not in crash
        print('PASS 3 HTTP pause/resume cycles, stable process, no new crash',flush=True)
    finally:
        ui.adb('shell','am','force-stop','app.perch')
        write_preferences(old.encode())
        ui.adb('shell','am','start','-n','app.perch/.MainActivity','-f','0x10008000')
        server.shutdown()
        server.server_close()
        print('Restored dedicated emulator demo preferences',flush=True)

if __name__=='__main__': run()
