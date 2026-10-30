"""Native Markdown layout fixture on a dedicated emulator; restores demo prefs."""
import json
import subprocess
import time
import xml.etree.ElementTree as ET
import android_smoke as ui

BODY = '''# 把信息，整理得清楚一些
正文、**重点**与 <inline> 采用统一的阅读节奏。

## 今天的进展
- **消息接收**：状态正常
  - 锁屏与后台接收
  - 离线消息补收
- **阅读体验**：原生 Compose 排版

### 下一步
3. 查看完整表格
4. 复制一段示例代码

> 适度的留白，用来区分内容，而不是堆在页头。

## 服务概览
| 服务 | 状态 | 延迟 | 最近同步时间 |
| :--- | :--- | ---: | :--- |
| 消息接收 | 运行正常 | 42 ms | 2026-10-29 09:00 |
| 历史补收 | 已完成 | 86 ms | 2026-10-29 09:01 |

## 一段可以复制的代码
~~~python
# 把结果整理后，投递到手机
def notify(title, message):
    payload = {
        "title": title,
        "message": message,
        "priority": 3,
    }
    return payload
~~~

更多示例见 [ntfy 文档](https://docs.ntfy.sh)。
'''.replace('<inline>', chr(96) + 'inline code' + chr(96))

def write_preferences(raw):
    subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'run-as', 'app.perch', 'tee', 'shared_prefs/perch.xml'], input=raw, capture_output=True, check=True, timeout=20)

def scroll_until(text):
    for attempt in range(6):
        if any(text in n.attrib.get('text', '') or text in n.attrib.get('content-desc', '') for n in ui.nodes()):
            return
        ui.adb('shell', 'input', 'swipe', '540', '1800', '540', '800', '350')
    raise AssertionError('Cannot scroll to ' + text)

def run():
    assert ui.SERIAL.startswith('emulator-'), 'Use a dedicated emulator only'
    data, old = ui.state()
    assert not data.get('url'), 'This fixture is limited to demo mode; no user server config may be replaced'
    fixture = dict(data, url='https://layout.invalid', enabled=False, appearance='light', topics=[{'name': 'reading', 'label': '排版样张', 'muted': True, 'joined': 0}], messages=[{'id': 'layout', 'topic': 'reading', 'title': '阅读排版样张', 'body': BODY, 'time': int(time.time()), 'priority': 3, 'read': False, 'server': 'https://layout.invalid', 'markdown': True}])
    root = ET.Element('map')
    ET.SubElement(root, 'string', name='data').text = json.dumps(fixture, ensure_ascii=False)
    ET.SubElement(root, 'string', name='token').text = ''
    ui.adb('shell', 'am', 'force-stop', 'app.perch')
    try:
        write_preferences(ET.tostring(root, encoding='utf-8', xml_declaration=True))
        ui.adb('shell', 'am', 'start', '-n', 'app.perch/.MainActivity')
        ui.tap_text('阅读排版样张')
        ui.assert_text('今天的进展')
        ui.screenshot('13-reading-hierarchy')
        scroll_until('左右滑动查看完整表格')
        ui.screenshot('14-reading-wide-table')
        cell = next(n for n in ui.nodes() if n.attrib.get('text') == '服务')
        _, y = ui.center(cell)
        ui.adb('shell', 'input', 'swipe', '900', y, '170', y, '400')
        ui.assert_text('最近同步时间')
        ui.screenshot('14b-reading-table-scrolled')
        scroll_until('复制代码')
        ui.screenshot('14c-reading-code')
        print('PASS nested and ordered lists, heading hierarchy, inline code, wide table horizontal scroll, Python code', flush=True)
    finally:
        ui.adb('shell', 'am', 'force-stop', 'app.perch')
        write_preferences(old.encode())
        ui.adb('shell', 'am', 'start', '-n', 'app.perch/.MainActivity')
    try:
        # Android may restore the previously selected settings tab after restart.
        ui.tap_text('收件箱', last=True)
        ui.assert_text('连接 ntfy')
        assert not any(n.attrib.get('text') == 'PERCH' for n in ui.nodes())
        ui.adb('shell', 'wm', 'size', '840x1867')
        ui.assert_text('筛选主题')
        ui.screenshot('11-inbox-320dp')
        ui.tap_text('新的想法，已经准备就绪。')
        ui.assert_text('项目进展')
        ui.screenshot('12-detail-320dp')
        ui.tap_text('返回')
        print('PASS 320dp compact inbox + native Markdown detail; no branding row', flush=True)
    finally:
        ui.adb('shell', 'wm', 'size', 'reset')

if __name__ == '__main__':
    run()
