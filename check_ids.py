#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Verify the module's resource-id name lists against every R$id in the 5.7.2 tree."""
import os, re, collections, json

import os as _os
_HERE = _os.path.dirname(_os.path.abspath(__file__))
_CAND = [_os.environ.get("CAMPUS_ROOT"), _os.path.dirname(_HERE)]
ROOT = next((c for c in _CAND if c and _os.path.isdir(_os.path.join(c, "源码", "tmall-campus-5.7.2-decompiled"))),
            r"D:\DeepSeekHarmess\Main\tmall-campus")
SRC = _os.path.join(ROOT, "源码", "tmall-campus-5.7.2-decompiled")
print(f"[root] {ROOT}")

LISTS = {
    "HOME_AD_IDS": [
        "cl_ad_container", "fl_ad_container", "iv_fix_banner", "iv_invite_banner",
        "seaview_banner", "rv_product_banner", "iv_banner", "banner_fl", "ad_container",
    ],
    "WEB_AD_IDS": [
        "fl_ad_render", "fl_ad", "cl_ad_top", "cl_ad_tag", "iv_close_ad",
        "cl_ad", "icon_ad_container", "fl_ad_tag", "ll_ad_tag",
    ],
    "BOTTOM_TAB_IDS": ["tl_main"],
}

decl = re.compile(r"public static(?: final)? int (\w+)\s*=")
table = collections.defaultdict(set)

rid_files = 0
for dp, dn, fn in os.walk(SRC):
    for f in fn:
        if f != "R$id.java":
            continue
        rid_files += 1
        path = os.path.join(dp, f)
        pkg = path[len(SRC) + 1:].replace(os.sep, ".").replace(".R$id.java", "")
        try:
            body = open(path, encoding="utf-8").read()
        except Exception:
            continue
        for m in decl.finditer(body):
            table[m.group(1)].add(pkg)

print(f"R$id.java 文件数: {rid_files}，去重 id 名数量: {len(table)}\n")

total_ok = total_miss = 0
report = {}
for list_name, names in LISTS.items():
    print(f"=== {list_name} ===")
    report[list_name] = {}
    for n in names:
        pkgs = table.get(n)
        if pkgs:
            total_ok += 1
            # keep it short: show the shortest package paths
            shown = ", ".join(sorted(pkgs, key=len)[:3])
            print(f"  OK    {n:<28} <- {shown}")
            report[list_name][n] = sorted(pkgs)
        else:
            total_miss += 1
            print(f"  MISS  {n}")
            report[list_name][n] = []

print(f"\n命中 {total_ok} / 缺失 {total_miss}")

out = r"D:\DeepSeekHarmess\Main\tmall-campus\work\id_check.json"
with open(out, "w", encoding="utf-8") as f:
    json.dump(report, f, ensure_ascii=False, indent=2)
print("->", out)
