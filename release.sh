#!/usr/bin/env bash
# Builds the signed release APK and makes a DRAFT GitHub release vX.Y.Z with it (publish it on
# GitHub when ready). The version is jdvr.version in gradle.properties.
#
#   ./release.sh               notes: the commits since the last release
#   ./release.sh notes.md      notes from a file
#
# Needs: the release key (see app/build.gradle.kts), gh signed in, a clean tree pushed to origin/main.
set -euo pipefail
cd "$(dirname "$0")"

# The release key's certificate: an APK signed with anything else wouldn't install as an update.
CERT_SHA256=7f5075d95c221044f18e48fbab4c868fd01bdf502463d687317438bd65a118c1

export JAVA_HOME="${JAVA_HOME:-$HOME/opt/jdk21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

die() { echo "release.sh: $*" >&2; exit 1; }

version=$(sed -n 's/^jdvr\.version=//p' gradle.properties)
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "jdvr.version in gradle.properties isn't X.Y.Z: '$version'"
tag="v$version"

[[ -z $(git status --porcelain) ]] || die "uncommitted changes; commit them first"
[[ $(git branch --show-current) == main ]] || die "not on main"
git fetch -q origin
[[ $(git rev-parse HEAD) == $(git rev-parse origin/main) ]] || die "main isn't the same as origin/main; push (or pull) first"
if gh release view "$tag" >/dev/null 2>&1; then
    die "$tag already exists on GitHub; raise jdvr.version in gradle.properties"
fi

./gradlew -q :app:assembleRelease

apk=app/build/outputs/apk/release/app-release.apk
apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)
"$apksigner" verify --print-certs "$apk" | grep -q "SHA-256 digest: $CERT_SHA256" \
    || die "the APK isn't signed with the JDVR release key (is the key set up in ~/.gradle/gradle.properties?)"

out=build/release
rm -rf "$out"
mkdir -p "$out"
cp "$apk" "$out/jdvr.apk"
(cd "$out" && sha256sum jdvr.apk > jdvr.apk.sha256)
sum=$(cut -d' ' -f1 "$out/jdvr.apk.sha256")

notes="$out/notes.md"
if [[ $# -ge 1 ]]; then
    cp "$1" "$notes"
else
    last=$(gh release list --exclude-drafts --limit 1 --json tagName -q '.[0].tagName // empty')
    {
        echo "## Changes"
        echo
        if [[ -n $last ]]; then git log --format='- %s' "$last..HEAD"; else echo "- First release."; fi
    } > "$notes"
fi
repo=$(gh repo view --json nameWithOwner -q .nameWithOwner)
cat >> "$notes" <<EOF

## Install

On the TV, in the Downloader app (or any browser that can install apps), open:

\`https://github.com/$repo/releases/latest/download/jdvr.apk\`

Or from a computer: \`adb install -r jdvr.apk\`. An update installs over the version before and keeps you signed in.

SHA-256 of jdvr.apk: \`$sum\`
EOF

gh release create "$tag" --draft --target "$(git rev-parse HEAD)" --title "JDVR $version" \
    --notes-file "$notes" "$out/jdvr.apk" "$out/jdvr.apk.sha256"
echo "Draft $tag made (versionName $version). Publish it on GitHub when ready."
