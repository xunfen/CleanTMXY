#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Robust downloader: url -> file, with progress, retries, and branch fallback for repos."""
import os, sys, ssl, time, zipfile, urllib.request, urllib.error, io

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"}


def fetch(url, tries=3, timeout=180):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
                return r.read()
        except Exception as e:
            last = e
            time.sleep(1.5 * (i + 1))
    raise last


def download(url, dest, tries=3):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print(f"  SKIP (exists {os.path.getsize(dest)/1048576:.2f} MB): {dest}")
        return True
    print(f"  GET {url}")
    try:
        data = fetch(url, tries=tries)
    except Exception as e:
        print(f"  FAIL {url}: {e}")
        return False
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "wb") as f:
        f.write(data)
    print(f"  OK  {len(data)/1048576:.2f} MB -> {dest}")
    return True


def repo_zip(repo, dest_dir):
    """Download a GitHub repo zip, trying common branches. Extract into dest_dir."""
    name = repo.split("/")[-1]
    out = os.path.join(dest_dir, name)
    if os.path.isdir(out) and os.listdir(out):
        print(f"  SKIP repo (exists): {out}")
        return out
    for br in ("main", "master"):
        url = f"https://codeload.github.com/{repo}/zip/refs/heads/{br}"
        try:
            data = fetch(url, tries=2)
        except Exception as e:
            print(f"  branch {br} failed: {e}")
            continue
        os.makedirs(out, exist_ok=True)
        z = zipfile.ZipFile(io.BytesIO(data))
        z.extractall(out)
        print(f"  OK repo {repo} ({br}) {len(data)/1048576:.2f} MB -> {out}")
        return out
    # fall back to API to learn default branch
    try:
        import json
        info = json.loads(fetch(f"https://api.github.com/repos/{repo}"))
        br = info.get("default_branch", "main")
        url = f"https://codeload.github.com/{repo}/zip/refs/heads/{br}"
        data = fetch(url)
        os.makedirs(out, exist_ok=True)
        zipfile.ZipFile(io.BytesIO(data)).extractall(out)
        print(f"  OK repo {repo} (api:{br}) -> {out}")
        return out
    except Exception as e:
        print(f"  FAIL repo {repo}: {e}")
        return None


if __name__ == "__main__":
    ROOT = r"D:\DeepSeekHarmess\Main\tmall-campus"
    T = os.path.join(ROOT, "tools")
    R = os.path.join(ROOT, "repos")

    print("== tools ==")
    download("https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip",
             os.path.join(T, "jadx.zip"))
    download("https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar",
             os.path.join(T, "apktool.jar"))
    download("https://github.com/JesusFreke/smali/releases/download/v2.5.2/baksmali-2.5.2.jar",
             os.path.join(T, "baksmali.jar"))
    download("https://github.com/JesusFreke/smali/releases/download/v2.5.2/smali-2.5.2.jar",
             os.path.join(T, "smali.jar"))
    download("https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip",
             os.path.join(T, "dex-tools.zip"))

    print("== repos ==")
    for repo in ["VellowK/Kaltsit-Campus",
                 "Xposed-Modules-Repo/com.puretools.campus",
                 "xiaojie-yahu/TmallCampus-AdBlock",
                 "fyfhcgch/tmall"]:
        repo_zip(repo, R)

    print("== extract jadx ==")
    jz = os.path.join(T, "jadx.zip")
    if os.path.exists(jz) and not os.path.isdir(os.path.join(T, "jadx")):
        zipfile.ZipFile(jz).extractall(os.path.join(T, "jadx"))
        print("  extracted jadx")
    print("DONE")
