#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
adb=${ADB:-adb}
jar=${HELPER_JAR:-"$here/helper.jar"}
[ -f "$jar" ] || { echo "Set HELPER_JAR to the built helper.jar, or use the release bundle." >&2; exit 1; }
[ -f "$here/start-device.sh" ] || { echo "Missing start-device.sh." >&2; exit 1; }
# Forward optional ADB arguments, e.g. ./start-helper.sh -s SERIAL.
token_file=$(mktemp "${TMPDIR:-/tmp}/bigfatfish.XXXXXXXX")
token=${token_file##*/}
remote_jar=/data/local/tmp/$token.jar
remote_script=/data/local/tmp/$token.sh
cleanup() {
    rm -f "$token_file"
    "$adb" "$@" shell rm -f "$remote_jar" "$remote_script" >/dev/null 2>&1 || true
}
trap 'cleanup "$@"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
"$adb" "$@" push "$jar" "$remote_jar"
"$adb" "$@" push "$here/start-device.sh" "$remote_script"
"$adb" "$@" shell sh "$remote_script" "$remote_jar"
