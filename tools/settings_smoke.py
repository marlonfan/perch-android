"""Verify icon navigation, read-all menu action, inline search, compact topic filter and native battery request."""
import android_smoke as ui

assert ui.SERIAL == 'emulator-5556', 'Dedicated review emulator only'
assert not ui.state()[0].get('url'), 'Demo configuration required'
before = ui.adb('shell', 'dumpsys', 'deviceidle', 'whitelist')
ui.tap_text('收件箱', last=True)
ui.assert_text('搜索消息')
ui.tap_text('全部')
ui.field(0, 'nas')
ui.assert_text('服务器需要你看一眼')
assert not any(n.attrib.get('text') == '新的想法，已经准备就绪。' for n in ui.nodes()), 'Search should filter messages by body, case-insensitively'
ui.field(0, 'perch-no-such-message')
ui.assert_text('没有匹配的消息')
ui.tap_text('清除搜索')
ui.adb('shell', 'input', 'keyevent', '4')
ui.assert_text('新的想法，已经准备就绪。')
assert not any(n.attrib.get('text') == '全部标为已读' or n.attrib.get('content-desc') == '全部标为已读' for n in ui.nodes()), 'Read-all should only appear inside the topic menu'
ui.tap_text('筛选主题')
ui.assert_text('所有主题')
ui.assert_text('全部标为已读')
ui.tap_text('全部标为已读')
ui.assert_text('未读 0')
assert not any(n.attrib.get('text') == '全部标为已读' or n.attrib.get('content-desc') == '全部标为已读' for n in ui.nodes()), 'Read-all menu should close after clicking'
ui.tap_text('筛选主题')
ui.assert_text('所有主题')
ui.assert_text('全部标为已读')
read_all = [n for n in ui.nodes() if n.attrib.get('text') == '全部标为已读']
assert read_all and all(n.attrib.get('enabled') == 'false' for n in read_all), 'Read-all menu item should be disabled without unread messages'
ui.tap_text('所有主题')
ui.tap_text('设置', last=True)
ui.tap_text('锁屏接收')
ui.tap_text('去设置')
try:
    activities = ui.adb('shell', 'dumpsys', 'activity', 'activities')
    assert 'RequestIgnoreBatteryOptimizations' in activities, 'Expected native Android battery exemption confirmation'
    ui.screenshot('23-native-battery-request')
finally:
    ui.adb('shell', 'input', 'keyevent', '4')
after = ui.adb('shell', 'dumpsys', 'deviceidle', 'whitelist')
assert before == after, 'Opening/cancelling settings must not change battery exemption'
ui.assert_text('锁屏接收')
ui.tap_text('收件箱', last=True)
print('PASS icon navigation, read-all menu action, inline search, compact topic filter, native battery confirmation + cancel without whitelist changes', flush=True)
