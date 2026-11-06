#!/usr/bin/env python3
"""查后端运行依赖的已知漏洞（OSV 数据库，只发送包名和版本）。有未登记的漏洞时退出码为 1。

用法：python3 scripts/dep-scan.py dependency-list.txt
依赖列表这样生成：cd backend && mvn -q dependency:list -DincludeScope=runtime -DoutputFile=dependency-list.txt

判断过不影响系统的漏洞登记在 scripts/dep-scan-allow.txt，每条要写清理由。
"""
import json
import os
import re
import sys
import urllib.request

ALLOW_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dep-scan-allow.txt")


def allowed():
    out = {}
    for line in open(ALLOW_FILE, encoding="utf8"):
        line = line.strip()
        if line and not line.startswith("#"):
            vuln, _, reason = line.partition(" ")
            out[vuln] = reason.strip()
    return out


def packages(path):
    found = []
    for line in open(path, encoding="utf8"):
        m = re.match(r"\s*([\w.\-]+):([\w.\-]+):jar:(?:[\w\-]+:)?([\w.\-]+):(\w+)", line.strip())
        if m:
            found.append((m[1], m[2], m[3]))
    return found


def query(pkgs):
    body = {"queries": [{"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v} for g, a, v in pkgs]}
    req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=60))["results"]


def main():
    pkgs = packages(sys.argv[1])
    if not pkgs:
        print("依赖列表是空的，检查 mvn dependency:list 的输出")
        return 1
    allow = allowed()
    new = []
    seen = set()
    for pkg, res in zip(pkgs, query(pkgs)):
        for v in res.get("vulns", []):
            seen.add(v["id"])
            if v["id"] not in allow:
                new.append((":".join(pkg), v["id"]))
    print(f"扫描 {len(pkgs)} 个依赖，发现 {len(seen)} 条记录，其中已登记 {len(seen & set(allow))} 条")
    for pkg, vuln in new:
        print(f"新漏洞：{pkg} https://osv.dev/vulnerability/{vuln}")
    for vuln in set(allow) - seen:
        print(f"已不再命中，可以从登记表删除：{vuln}")
    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main())
