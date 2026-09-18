#!/usr/bin/env bash
set -euo pipefail

echo "========== OPERATING SYSTEM =========="
cat /etc/os-release

echo
echo "========== KERNEL =========="
uname -r

echo
echo "========== CPU =========="
nproc

echo
echo "========== MEMORY =========="
free -h

echo
echo "========== BLOCK DEVICES =========="
lsblk -o NAME,SIZE,TYPE,FSTYPE,MOUNTPOINTS,SERIAL

echo
echo "========== FILESYSTEMS =========="
df -hT

echo
echo "========== EXISTING MOUNTS =========="
findmnt /u01 || true

echo
echo "========== REPOSITORIES =========="
dnf repolist
