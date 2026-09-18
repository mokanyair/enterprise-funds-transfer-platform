#!/usr/bin/env bash
set -euo pipefail

# Phase 6.2 — Oracle database storage
# Executed on RHEL through AWS Systems Manager.
#
# Default: validation only.
# Formatting requires ALLOW_FORMAT=true.

EXPECTED_VOLUME="vol039a7bf67d2169277"
EXPECTED_SIZE_BYTES=107374182400
MOUNT_POINT="/u01"
ALLOW_FORMAT="${ALLOW_FORMAT:-false}"

DEVICE_LINK="/dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_${EXPECTED_VOLUME}"

log() {
    printf '[INFO] %s\n' "$*"
}

fail() {
    printf '[ERROR] %s\n' "$*" >&2
    exit 1
}

log "Starting Phase 6.2 storage validation"

# ---------------------------------------------------------
# 1. Validate execution environment
# ---------------------------------------------------------

[[ "$EUID" -eq 0 ]] ||
    fail "Script must run as root."

[[ "$ALLOW_FORMAT" == "true" || "$ALLOW_FORMAT" == "false" ]] ||
    fail "ALLOW_FORMAT must be true or false."

for command in \
    lsblk \
    findmnt \
    blkid \
    wipefs \
    blockdev \
    readlink \
    mount \
    mkfs.xfs; do

    command -v "$command" >/dev/null 2>&1 ||
        fail "Required command is missing: $command"

done

# ---------------------------------------------------------
# 2. Identify EBS volume using its unique volume ID
# ---------------------------------------------------------

[[ -L "$DEVICE_LINK" ]] ||
    fail "Expected EBS volume link not found: $DEVICE_LINK"

DEVICE="$(readlink -f "$DEVICE_LINK")"

[[ -b "$DEVICE" ]] ||
    fail "Resolved path is not a block device: $DEVICE"

[[ "$(lsblk -dn -o TYPE "$DEVICE")" == "disk" ]] ||
    fail "Target is not a whole disk."

SERIAL="$(lsblk -dn -o SERIAL "$DEVICE" | tr -d '[:space:]')"

[[ "$SERIAL" == "$EXPECTED_VOLUME" ]] ||
    fail "Disk serial does not match the approved EBS volume."

log "Validated EBS volume: $EXPECTED_VOLUME"
log "Resolved Linux device: $DEVICE"

# ---------------------------------------------------------
# 3. Verify capacity
# ---------------------------------------------------------

ACTUAL_SIZE="$(blockdev --getsize64 "$DEVICE")"

[[ "$ACTUAL_SIZE" -eq "$EXPECTED_SIZE_BYTES" ]] ||
    fail "Unexpected disk size: $ACTUAL_SIZE bytes"

log "Validated disk capacity: 100 GiB"

# ---------------------------------------------------------
# 4. Exclude the operating-system disk
# ---------------------------------------------------------

ROOT_SOURCE="$(findmnt -n -o SOURCE /)"

[[ -n "$ROOT_SOURCE" ]] ||
    fail "Unable to identify root filesystem."

ROOT_SOURCE="$(readlink -f "$ROOT_SOURCE")"

ROOT_PARENT="$(lsblk -no PKNAME "$ROOT_SOURCE" | head -n 1)"

[[ -n "$ROOT_PARENT" ]] ||
    fail "Unable to identify root disk."

ROOT_DISK="/dev/$ROOT_PARENT"

[[ "$DEVICE" != "$ROOT_DISK" ]] ||
    fail "Target is the operating-system disk."

log "Root disk excluded: $ROOT_DISK"

# ---------------------------------------------------------
# 5. Inspect existing partitions and signatures
# ---------------------------------------------------------

PARTITION_COUNT="$(
    lsblk -nr -o TYPE "$DEVICE" |
    grep -c '^part$' || true
)"

[[ "$PARTITION_COUNT" -eq 0 ]] ||
    fail "Existing partitions detected. Refusing changes."

SIGNATURES="$(wipefs -n "$DEVICE")"

EXISTING_FS="$(blkid -s TYPE -o value "$DEVICE" 2>/dev/null || true)"

if [[ -n "$EXISTING_FS" && "$EXISTING_FS" != "xfs" ]]; then
    fail "Unexpected filesystem detected: $EXISTING_FS"
fi

# ---------------------------------------------------------
# 6. Validate mount point and fstab before any formatting
# ---------------------------------------------------------

if [[ -L "$MOUNT_POINT" ]]; then
    fail "$MOUNT_POINT is a symbolic link."
fi

if [[ -e "$MOUNT_POINT" && ! -d "$MOUNT_POINT" ]]; then
    fail "$MOUNT_POINT exists but is not a directory."
fi

# Never mount over existing files.
if [[ -d "$MOUNT_POINT" ]] &&
   ! findmnt -rn --mountpoint "$MOUNT_POINT" >/dev/null; then

    if [[ -n "$(find "$MOUNT_POINT" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
        fail "$MOUNT_POINT contains files. Refusing to mount over them."
    fi
fi

# Reject unexpected existing fstab configuration.
EXISTING_FSTAB="$(
    awk -v mp="$MOUNT_POINT" '
        $1 !~ /^#/ && $2 == mp { print }
    ' /etc/fstab
)"

FSTAB_COUNT="$(
    awk -v mp="$MOUNT_POINT" '
        $1 !~ /^#/ && $2 == mp { count++ }
        END { print count+0 }
    ' /etc/fstab
)"

[[ "$FSTAB_COUNT" -le 1 ]] ||
    fail "Multiple fstab entries found for $MOUNT_POINT."

# ---------------------------------------------------------
# 7. Validation-only execution
# ---------------------------------------------------------

if [[ -z "$EXISTING_FS" ]]; then

    [[ -z "$SIGNATURES" ]] ||
        fail "Existing disk signatures detected. Refusing formatting."

    # Ensure disk is not mounted anywhere.
    if lsblk -nr -o MOUNTPOINTS "$DEVICE" | grep -q '[^[:space:]]'; then
        fail "Disk or a child device is mounted."
    fi

    # Reject existing fstab references to the raw device.
    if awk -v dev="$DEVICE" -v link="$DEVICE_LINK" '
        $1 !~ /^#/ && ($1 == dev || $1 == link) { found=1 }
        END { exit !found }
    ' /etc/fstab; then
        fail "Disk already has an fstab reference."
    fi

    [[ -z "$EXISTING_FSTAB" ]] ||
        fail "$MOUNT_POINT already has an fstab entry."

    if [[ "$ALLOW_FORMAT" != "true" ]]; then
        log "Blank disk validated."
        log "Formatting is disabled."
        log "Run with ALLOW_FORMAT=true after approval."
        echo "DATABASE_STORAGE_VALIDATED"
        exit 0
    fi

    # -----------------------------------------------------
    # 8. Create filesystem — explicitly approved operation
    # -----------------------------------------------------

    log "Formatting approved blank disk: $DEVICE"

    mkfs.xfs "$DEVICE"

    EXISTING_FS="$(blkid -s TYPE -o value "$DEVICE")"

    [[ "$EXISTING_FS" == "xfs" ]] ||
        fail "Filesystem creation verification failed."

else

    [[ "$EXISTING_FS" == "xfs" ]] ||
        fail "Unexpected filesystem."

    # Existing XFS is accepted only if it is the approved
    # disk and has no unexpected additional signatures.
    if [[ -n "$SIGNATURES" ]] &&
       echo "$SIGNATURES" | grep -Eq 'gpt|dos|LVM2_member|crypto_LUKS'; then
        fail "Unexpected disk signature detected."
    fi

    log "Existing XFS filesystem detected; formatting skipped."

fi

# ---------------------------------------------------------
# 9. Obtain filesystem UUID
# ---------------------------------------------------------

UUID="$(blkid -s UUID -o value "$DEVICE")"

[[ -n "$UUID" ]] ||
    fail "Unable to retrieve filesystem UUID."

EXPECTED_FSTAB="UUID=$UUID $MOUNT_POINT xfs defaults,nofail 0 2"

if [[ -n "$EXISTING_FSTAB" ]]; then

    EXISTING_SOURCE="$(
        printf '%s\n' "$EXISTING_FSTAB" | awk '{print $1}'
    )"

    [[ "$EXISTING_SOURCE" == "UUID=$UUID" ]] ||
        fail "Existing fstab entry points to another filesystem."

    EXISTING_TYPE="$(
        printf '%s\n' "$EXISTING_FSTAB" | awk '{print $3}'
    )"

    [[ "$EXISTING_TYPE" == "xfs" ]] ||
        fail "Existing fstab filesystem type is not XFS."

fi

# Reject a UUID configured for another mount point.
if awk -v uuid="UUID=$UUID" -v mp="$MOUNT_POINT" '
    $1 !~ /^#/ && $1 == uuid && $2 != mp { found=1 }
    END { exit !found }
' /etc/fstab; then
    fail "Filesystem UUID is configured for another mount point."
fi

# ---------------------------------------------------------
# 10. Create mount point and mount filesystem
# ---------------------------------------------------------

mkdir -p "$MOUNT_POINT"

if findmnt -rn --mountpoint "$MOUNT_POINT" >/dev/null; then

    MOUNTED_UUID="$(
        findmnt -n -o UUID --mountpoint "$MOUNT_POINT"
    )"

    [[ "$MOUNTED_UUID" == "$UUID" ]] ||
        fail "$MOUNT_POINT is mounted from a different filesystem."

    log "Correct filesystem is already mounted."

else

    # Do not mount the same filesystem at another location.
    if findmnt -rn -S "UUID=$UUID" >/dev/null; then
        fail "Filesystem is already mounted elsewhere."
    fi

    mount -t xfs "UUID=$UUID" "$MOUNT_POINT"

    log "Mounted filesystem at $MOUNT_POINT"

fi

# ---------------------------------------------------------
# 11. Configure persistent mounting
# ---------------------------------------------------------

if [[ -z "$EXISTING_FSTAB" ]]; then

    cp -a /etc/fstab "/etc/fstab.phase6-2.$(date +%Y%m%d%H%M%S).bak"

    printf '%s\n' "$EXPECTED_FSTAB" >> /etc/fstab

    log "Persistent fstab entry created."

else

    log "Existing fstab entry validated."

fi

systemctl daemon-reload

# ---------------------------------------------------------
# 12. Final validation
# ---------------------------------------------------------

FINAL_UUID="$(
    findmnt -n -o UUID --mountpoint "$MOUNT_POINT"
)"

[[ "$FINAL_UUID" == "$UUID" ]] ||
    fail "Final mount UUID verification failed."

[[ "$(findmnt -n -o FSTYPE --mountpoint "$MOUNT_POINT")" == "xfs" ]] ||
    fail "Final filesystem type verification failed."

log "Final filesystem:"
df -hT "$MOUNT_POINT"

log "Final mount:"
findmnt "$MOUNT_POINT"

log "Filesystem UUID: $UUID"

echo "DATABASE_STORAGE_SUCCESS"
