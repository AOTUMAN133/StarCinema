#!/usr/bin/env python3
"""切 EmbyTV md_theme_* 调色板到 AfuseKtV 深色(修复版)
只替换 <color name="md_theme_...">VALUE</color> 的 VALUE, 不碰标签
"""
import re, sys

EMBY = '/root/EmbyTV/app/src/main/res/values/colors.xml'
AF = '/root/afusektv_res/apktool/res/values/colors.xml'

af_src = open(AF, encoding='utf-8').read()
af_theme = dict(re.findall(r'<color name="(md_theme_[^"]+)">([^<]+)</color>', af_src))
print(f'AfuseKtV: {len(af_theme)} 项')

cur = open(EMBY, encoding='utf-8').read()
before = len(re.findall(r'<color name="md_theme_', cur))

count = 0
missing = []
def repl(m):
    global count
    name, val = m.group(1), m.group(2)
    base = name.replace('_mediumContrast', '')
    if base in af_theme and af_theme[base] != val:
        count += 1
        return f'<color name="{name}">{af_theme[base]}</color>'
    elif base not in af_theme:
        missing.append(name)
    return m.group(0)

new = re.sub(r'<color name="(md_theme_[^"]+)">([^<]+)</color>', repl, cur)
open(EMBY, 'w', encoding='utf-8').write(new)

after = len(re.findall(r'<color name="md_theme_', new))
print(f'EmbyTV: {before} → {after} 项, 替换 {count} 个值')
if missing:
    print(f'AfuseKtV 缺失的: {missing[:8]}')

# 验证关键色
for k in ['md_theme_background', 'md_theme_onSurface', 'md_theme_primary', 'md_theme_onPrimary']:
    m = re.search(rf'<color name="{k}">([^<]+)</color>', new)
    print(f'  {k} = {m.group(1) if m else "!!缺失"}')