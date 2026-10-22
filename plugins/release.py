#!/usr/bin/env python3
"""Releases one JellyDVR plugin: builds it, makes GitHub release v<version> in the plugin's repo with
<repo>_<version>.zip (the DLL and icon.png), and adds that version to plugins/manifest.json, the plugin
repository Jellyfin users add. A plugin repo with its own manifest.json (Comskip) gets the entry too.

    plugins/release.py ~/Downloads/JellyfinDvrSpace "What changed, in a sentence or two."

The version is AssemblyVersion in the plugin's .csproj; raise it before releasing again. Needs gh signed
in and the plugin's tree committed and pushed. Commit and push plugins/manifest.json afterwards.
"""
import datetime as dt
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(HERE, "manifest.json")


def die(msg):
    sys.exit("release.py: " + msg)


def run(cmd, cwd, **kw):
    return subprocess.run(cmd, cwd=cwd, check=True, text=True, **kw)


def out(cmd, cwd):
    return run(cmd, cwd, capture_output=True).stdout.strip()


def add_version(path, plugin, entry):
    manifest = json.load(open(path)) if os.path.exists(path) else []
    item = next((p for p in manifest if p["guid"] == plugin["guid"]), None)
    if item is None:
        item = dict(plugin, versions=[])
        manifest.append(item)
    if any(v["version"] == entry["version"] for v in item["versions"]):
        die(f"{path} already lists {plugin['name']} {entry['version']}")
    item["versions"].insert(0, entry)
    with open(path, "w") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")


def main():
    if len(sys.argv) != 3:
        die("usage: release.py <plugin repo folder> <changelog>")
    src, changelog = os.path.abspath(os.path.expanduser(sys.argv[1])), sys.argv[2]

    csproj = glob.glob(os.path.join(src, "*.csproj"))[0]
    project = open(csproj).read()
    version = re.search(r"<AssemblyVersion>([\d.]+)<", project).group(1)
    abi = re.search(r'"Jellyfin.Controller" Version="([\d.]+)"', project).group(1) + ".0"
    dll = os.path.splitext(os.path.basename(csproj))[0] + ".dll"
    meta = json.load(open(os.path.join(src, "meta.json")))

    remote = out(["git", "remote", "get-url", "origin"], src)
    owner, repo = re.search(r"github\.com[:/]([^/]+)/([^/.]+)", remote).groups()
    if out(["git", "status", "--porcelain"], src):
        die(f"{src} has uncommitted changes")
    run(["git", "fetch", "-q", "origin"], src)
    if out(["git", "rev-parse", "HEAD"], src) != out(["git", "rev-parse", "@{u}"], src):
        die(f"{src} isn't the same as its upstream; push (or pull) first")
    tag = "v" + version
    branch = out(["gh", "repo", "view", f"{owner}/{repo}", "--json", "defaultBranchRef", "-q", ".defaultBranchRef.name"], src)
    if subprocess.run(["gh", "release", "view", tag, "--repo", f"{owner}/{repo}"], capture_output=True).returncode == 0:
        die(f"{owner}/{repo} already has {tag}; raise AssemblyVersion")

    env = dict(os.environ, DOTNET_ROOT=os.environ.get("DOTNET_ROOT", os.path.expanduser("~/.dotnet")))
    dotnet = os.path.join(env["DOTNET_ROOT"], "dotnet")
    if not os.path.exists(dotnet):
        dotnet = "dotnet"
    run([dotnet, "build", "-c", "Release", "-v", "q", "-nologo"], src, env=env, stdout=subprocess.DEVNULL)

    folder = os.path.join(src, "bin", "release-zip")
    os.makedirs(folder, exist_ok=True)
    zip_path = os.path.join(folder, f"{repo}_{version}.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(src, "bin", "Release", "net10.0", dll), dll)
        z.write(os.path.join(src, "icon.png"), "icon.png")
    md5 = hashlib.md5(open(zip_path, "rb").read()).hexdigest()

    run(["gh", "release", "create", tag, zip_path, "--repo", f"{owner}/{repo}", "--title", version,
         "--target", out(["git", "rev-parse", "HEAD"], src), "--notes", changelog], src)

    plugin = {
        "guid": meta["guid"],
        "name": meta["name"],
        "description": meta["description"],
        "overview": meta.get("overview") or meta["description"],
        "owner": owner,
        "category": meta.get("category") or "Live TV",
        "imageUrl": f"https://raw.githubusercontent.com/{owner}/{repo}/{branch}/icon.png",
    }
    entry = {
        "version": version,
        "changelog": changelog,
        "targetAbi": abi,
        "sourceUrl": f"https://github.com/{owner}/{repo}/releases/download/{tag}/{repo}_{version}.zip",
        "checksum": md5,
        "timestamp": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.0000000Z"),
    }
    add_version(MANIFEST, plugin, entry)
    own = os.path.join(src, "manifest.json")
    if os.path.exists(own):
        add_version(own, plugin, entry)
        print(f"Also added to {own}: commit and push it in that repo.")
    print(f"Released {meta['name']} {version} ({md5}); commit and push plugins/manifest.json.")


main()
