#!/bin/sh
# Disables (or with --undo, enables again) the apps listed in scripts/debloat/<brand>.txt on the TV adb is
# connected to. Usage: scripts/debloat.sh <brand> [--undo] [--skip group,group] [--serial host:port]
set -e
list="$(dirname "$0")/debloat/$1.txt"
[ -n "$1" ] && [ -f "$list" ] || { echo "usage: $0 <$(ls "$(dirname "$0")/debloat" | sed 's/\.txt$//' | paste -sd '|' -)> [--undo] [--skip group,group] [--serial host:port]" >&2; exit 1; }
shift
undo=0 skip="" serial=""
while [ $# -gt 0 ]; do
    case "$1" in
        --undo) undo=1 ;;
        --skip) skip=",$2,"; shift ;;
        --serial) serial="-s $2"; shift ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
    shift
done
group=""
grep -v '^[[:space:]]*\(#\|$\)' "$list" | tr -d '\r' | while read -r line; do
    case "$line" in
        \[*\]) group=$(echo "$line" | tr -d '[]'); continue ;;
    esac
    case "$skip" in *",$group,"*) continue ;; esac
    if [ $undo = 1 ]; then
        out=$(adb $serial shell pm enable "$line" 2>&1 | tail -1)
    else
        out=$(adb $serial shell pm disable-user --user 0 "$line" 2>&1 | tail -1)
    fi
    printf '%-20s %-45s %s\n' "$group" "$line" "$out"
done
