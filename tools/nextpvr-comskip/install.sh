#!/bin/sh
# Installs the scanner for the current user on the NextPVR machine: script, comskip and its ini go
# in ~/comskip (comskip and comskip.ini must already be there, or beside this script), the service
# in ~/.config/systemd/user. No root needed. Run on the NextPVR machine.
set -e
here=$(cd "$(dirname "$0")" && pwd)
dest="$HOME/comskip"
mkdir -p "$dest" "$HOME/.config/systemd/user"
if [ "$here" != "$dest" ]; then
    cp "$here/nextpvr-comskip.py" "$here/nextpvr-comskip.conf.example" "$dest/"
    for f in comskip comskip.ini; do
        [ -e "$dest/$f" ] || cp "$here/$f" "$dest/"
    done
fi
[ -e "$dest/nextpvr-comskip.conf" ] || cp "$here/nextpvr-comskip.conf.example" "$dest/nextpvr-comskip.conf"
chmod +x "$dest/comskip" "$dest/nextpvr-comskip.py"
cp "$here/nextpvr-comskip.service" "$HOME/.config/systemd/user/"
systemctl --user daemon-reload
systemctl --user enable --now nextpvr-comskip.service
# Keep it running when nobody is logged in (allowed for your own user on most systems).
loginctl enable-linger "$USER" 2>/dev/null || echo "Couldn't keep it running after logout: ask an admin for 'loginctl enable-linger $USER'"
echo "Installed. Log: journalctl --user -u nextpvr-comskip -f"
