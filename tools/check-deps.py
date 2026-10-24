#!/usr/bin/env python3
# Prüft die externen Abhängigkeiten von Scidc2go: neuere stabile Versionen und
# bekannte Sicherheitslücken (OSV-Datenbank). Wird vor dem ersten Release eines
# Tages ausgeführt.
#
# Aufruf: tools/check-deps.py
# Exit-Code 0: alles aktuell, 1: Updates oder Sicherheitslücken gefunden, 2: Prüfung fehlgeschlagen.
import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GOOGLE = "https://dl.google.com/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"
UNSTABLE = re.compile(r"(alpha|beta|rc|dev|eap|snapshot|m\d)", re.I)


def fetch(url, data=None):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": "scidc2go-check-deps",
                                                          "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def vkey(v):
    return [int(x) if x.isdigit() else x for x in re.split(r"[.\-]", v)]


def latest_maven(group, artifact):
    for repo in (GOOGLE, CENTRAL) if group.startswith(("androidx", "com.android")) else (CENTRAL,):
        try:
            xml = fetch(f"{repo}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml")
        except Exception:
            continue
        versions = [v.text for v in ET.fromstring(xml).iter("version")]
        stable = [v for v in versions if not UNSTABLE.search(v)]
        if stable:
            return max(stable, key=vkey)
    return None


def maven_deps():
    """(Anzeigename, group, artifact, Version) aus den Gradle-Dateien."""
    deps = []
    top = (ROOT / "build.gradle.kts").read_text()
    plugins = {
        "com.android.application": ("com.android.tools.build", "gradle"),
        "org.jetbrains.kotlin.android": ("org.jetbrains.kotlin", "kotlin-gradle-plugin"),
    }
    for pid, version in re.findall(r'id\("([^"]+)"\) version "([^"]+)"', top):
        if pid in plugins:
            deps.append((f"Plugin {pid}", *plugins[pid], version))
    app = (ROOT / "app/build.gradle.kts").read_text()
    for g, a, v in re.findall(r'"([\w.\-]+):([\w.\-]+):([\w.\-]+)"', app):
        deps.append((f"{g}:{a}", g, a, v))
    return deps


def main():
    findings = 0
    failed = False
    print("Abhängigkeit".ljust(58), "aktuell".ljust(12), "neueste stabile")
    rows = []
    for name, g, a, v in maven_deps():
        latest = latest_maven(g, a)
        rows.append((name, g, a, v))
        mark = "" if latest == v else "  <- Update" if latest else "  (nicht prüfbar)"
        if latest is None:
            failed = True
        elif latest != v and vkey(latest) > vkey(v):
            findings += 1
        else:
            mark = ""
        print(name.ljust(58), v.ljust(12), (latest or "?") + mark)

    # Gradle-Wrapper
    props = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
    gv = re.search(r"gradle-([\d.]+)-", props).group(1)
    try:
        latest = json.loads(fetch("https://services.gradle.org/versions/current"))["version"]
    except Exception:
        latest, failed = None, True
    upd = latest and vkey(latest) > vkey(gv)
    findings += bool(upd)
    print("Gradle-Wrapper".ljust(58), gv.ljust(12), (latest or "?") + ("  <- Update" if upd else ""))

    # Stockfish
    sf = re.search(r"SF_TAG=(\S+)", (ROOT / "tools/build-stockfish.sh").read_text()).group(1)
    try:
        latest = json.loads(fetch("https://api.github.com/repos/official-stockfish/Stockfish/releases/latest"))["tag_name"]
    except Exception:
        latest, failed = None, True
    upd = latest and latest != sf
    findings += bool(upd)
    print("Stockfish".ljust(58), sf.ljust(12), (latest or "?") + ("  <- Update" if upd else ""))

    # Sicherheitslücken der verwendeten Versionen (OSV)
    print()
    queries = [{"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v} for _, g, a, v in rows]
    try:
        res = json.loads(fetch("https://api.osv.dev/v1/querybatch", json.dumps({"queries": queries}).encode()))
        vulns = [(rows[i][0], rows[i][3], [x["id"] for x in r.get("vulns", [])])
                 for i, r in enumerate(res["results"]) if r.get("vulns")]
        if vulns:
            for name, v, ids in vulns:
                print(f"SICHERHEITSLÜCKE: {name} {v}: {', '.join(ids)}")
            findings += len(vulns)
        else:
            print(f"Sicherheitslücken (OSV): keine bekannt in {len(queries)} Maven-Abhängigkeiten")
    except Exception as e:
        print(f"Sicherheitsprüfung fehlgeschlagen: {e}")
        failed = True

    print()
    if failed:
        print("Nicht alle Abhängigkeiten konnten geprüft werden.")
        return 2
    print("Alles aktuell." if findings == 0 else f"{findings} Hinweis(e) gefunden.")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
