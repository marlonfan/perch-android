"""Verify icon navigation, read-all menu and native battery request without accepting it."""
import android_smoke as ui

assert ui.SERIAL == 'emulator-5556', 'Dedicated review emulator only'
assert not ui.state()[0].get('url'), 'Demo configuration required'
before = ui.adb('shell', 'dumpsys', 'deviceidle', 'whitelist')
ui.tap_text('收件箱', last=True)
ui.tap_text('所有主题')
ui.tap_text('全部标为已读')
ui.assert_text('未读 0')
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
print('PASS icon navigation, read-all menu, native battery confirmation + cancel without whitelist changes', flush=True)
