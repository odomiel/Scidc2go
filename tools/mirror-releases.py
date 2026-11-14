#!/usr/bin/env python3
"""Überträgt die Releases von Forgejo in den GitHub-Mirror.

Code und Tags spiegelt Forgejo selbst (Push-Mirror); Releases mit Anhängen nicht. Dieses Skript legt
fehlende Releases auf GitHub an (älteste zuerst, das neueste als „latest“) und lädt fehlende Anhänge hoch.
Vorhandene Releases und Anhänge bleiben unverändert, es kann also beliebig oft laufen.

Von Forgejo wird nur gelesen; Repo und Releases sind öffentlich, deshalb ohne Token. Für GitHub:
~/.config/github/token (Fine-grained, Contents: Read and write). Jeder hochgeladene Anhang wird über die
SHA-256 geprüft, die GitHub zu ihm meldet.
Aufruf: python3 -I tools/mirror-releases.py
"""
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request

FJ = "https://forgejo.mydir.ipv64.net/api/v1/repos/forgejouser/scidc2go"
GH = "https://api.github.com/repos/odomiel/Scidc2go"
GH_UP = "https://uploads.github.com/repos/odomiel/Scidc2go"


def token(name):
    return open(os.path.expanduser(f"~/.config/{name}/token")).read().strip()


FJ_H = {}
GH_H = {"Authorization": f"Bearer {token('github')}", "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28"}


class _NoAuthToOtherHosts(urllib.request.HTTPRedirectHandler):
    """urllib gibt den Authorization-Kopf sonst bei Umleitungen auch an fremde Hosts weiter."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new is not None and urllib.parse.urlsplit(newurl).netloc != urllib.parse.urlsplit(req.full_url).netloc:
            new.remove_header("Authorization")
        return new


_opener = urllib.request.build_opener(_NoAuthToOtherHosts)


def call(url, headers, data=None, ctype=None, method=None):
    h = dict(headers)
    if ctype:
        h["Content-Type"] = ctype
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    with _opener.open(r, timeout=300) as resp:
        return resp.read()


def gh_json(url, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    return json.loads(call(url, GH_H, data, "application/json" if data else None))


def content_type(name):
    if name.endswith(".apk"):
        return "application/vnd.android.package-archive"
    if name.endswith(".gz"):
        return "application/gzip"
    return "application/octet-stream"


def main():
    rels = json.loads(call(f"{FJ}/releases?limit=100&draft=false", FJ_H))
    rels = [r for r in rels if not r["draft"]]
    rels.sort(key=lambda r: r["created_at"])
    if not rels:
        sys.exit("Keine Releases auf Forgejo")
    latest = rels[-1]["tag_name"]
    existing = {r["tag_name"]: r for r in gh_json(f"{GH}/releases?per_page=100")}

    for r in rels:
        tag = r["tag_name"]
        g = existing.get(tag)
        if g is None:
            g = gh_json(f"{GH}/releases", {
                "tag_name": tag, "name": r["name"], "body": r["body"], "draft": False,
                "prerelease": r["prerelease"], "make_latest": "true" if tag == latest else "false"})
            print(f"{tag}: angelegt")
        have = {a["name"] for a in g.get("assets", [])}
        for a in r["assets"]:
            if a["name"] in have:
                continue
            src = a["browser_download_url"]
            if urllib.parse.urlsplit(src).netloc != urllib.parse.urlsplit(FJ).netloc:
                sys.exit(f"{a['name']}: Download-Adresse {src} liegt nicht auf Forgejo")
            data = call(src, FJ_H)
            if len(data) != a["size"]:
                sys.exit(f"{a['name']}: {len(data)} statt {a['size']} Bytes geladen")
            sha = hashlib.sha256(data).hexdigest()
            url = f"{GH_UP}/releases/{g['id']}/assets?name={urllib.parse.quote(a['name'])}"
            up = json.loads(call(url, GH_H, data, content_type(a["name"]), "POST"))
            if up.get("digest") != f"sha256:{sha}":
                sys.exit(f"{a['name']}: GitHub meldet {up.get('digest')} statt sha256:{sha}")
            print(f"{tag}: {a['name']} hochgeladen (SHA-256 {sha[:12]}…)")
    # make_latest beim Anlegen übernimmt GitHub nicht immer; deshalb zum Schluss ausdrücklich setzen
    current = json.loads(call(f"{GH}/releases/latest", GH_H)).get("tag_name")
    if current != latest:
        rid = gh_json(f"{GH}/releases/tags/{latest}")["id"]
        call(f"{GH}/releases/{rid}", GH_H, json.dumps({"make_latest": "true"}).encode(), "application/json", "PATCH")
        print(f"{latest}: als neuestes Release markiert (war {current})")
    print("GitHub ist auf dem Stand von Forgejo")


if __name__ == "__main__":
    main()
