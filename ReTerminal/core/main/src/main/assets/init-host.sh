TERMINAL_DISTRIBUTION=${OMNIBOT_TERMINAL_DISTRIBUTION:-alpine}
case "$TERMINAL_DISTRIBUTION" in
  ubuntu) ;;
  *) TERMINAL_DISTRIBUTION=alpine ;;
esac

ROOTFS_DIR=$PREFIX/local/$TERMINAL_DISTRIBUTION
ROOTFS_ARCHIVE=$PREFIX/files/$TERMINAL_DISTRIBUTION.tar.gz
ROOTFS_LOCK_DIR="${ROOTFS_DIR}.install-lock"
ROOTFS_LOCK_PID_FILE="$ROOTFS_LOCK_DIR/pid"
ROOTFS_STAGING_DIR="${ROOTFS_DIR}.new.$$"
ROOTFS_PREVIOUS_DIR="${ROOTFS_DIR}.previous.$$"
ROOTFS_LOCK_HELD=0

[ ! -f "$ROOTFS_ARCHIVE" ] && ROOTFS_ARCHIVE=$PREFIX/files/$TERMINAL_DISTRIBUTION.tar

cleanup_rootfs_refresh() {
    if [ -d "$ROOTFS_PREVIOUS_DIR" ] && [ ! -d "$ROOTFS_DIR" ]; then
        mv "$ROOTFS_PREVIOUS_DIR" "$ROOTFS_DIR" 2>/dev/null || true
    fi
    rm -rf "$ROOTFS_STAGING_DIR"
    [ -d "$ROOTFS_DIR" ] && rm -rf "$ROOTFS_PREVIOUS_DIR"
    if [ "$ROOTFS_LOCK_HELD" = "1" ] &&
       [ "$(cat "$ROOTFS_LOCK_PID_FILE" 2>/dev/null || true)" = "$$" ]; then
        rm -rf "$ROOTFS_LOCK_DIR"
    fi
    ROOTFS_LOCK_HELD=0
}

mkdir -p "$(dirname "$ROOTFS_DIR")"
ROOTFS_LOCK_WAIT_SECONDS=0
ROOTFS_LOCK_EMPTY_WAITS=0
while ! mkdir "$ROOTFS_LOCK_DIR" 2>/dev/null; do
    ROOTFS_LOCK_OWNER=$(cat "$ROOTFS_LOCK_PID_FILE" 2>/dev/null || true)
    if [ -n "$ROOTFS_LOCK_OWNER" ]; then
        ROOTFS_LOCK_EMPTY_WAITS=0
        if ! kill -0 "$ROOTFS_LOCK_OWNER" 2>/dev/null; then
            rm -rf "$ROOTFS_LOCK_DIR"
            continue
        fi
    else
        ROOTFS_LOCK_EMPTY_WAITS=$((ROOTFS_LOCK_EMPTY_WAITS + 1))
        if [ "$ROOTFS_LOCK_EMPTY_WAITS" -ge 2 ]; then
            rm -rf "$ROOTFS_LOCK_DIR"
            continue
        fi
    fi
    if [ "$ROOTFS_LOCK_WAIT_SECONDS" -ge 120 ]; then
        echo "rootfs install lock timed out" >&2
        exit 75
    fi
    sleep 1
    ROOTFS_LOCK_WAIT_SECONDS=$((ROOTFS_LOCK_WAIT_SECONDS + 1))
done
ROOTFS_LOCK_HELD=1
printf '%s\n' "$$" > "$ROOTFS_LOCK_PID_FILE"
trap cleanup_rootfs_refresh 0
trap 'exit 1' 1 2 3 15

mkdir -p "$ROOTFS_DIR"

ROOTFS_NEEDS_REFRESH=0
EXPECTED_ROOTFS_VERSION=""
if [ "$TERMINAL_DISTRIBUTION" = "alpine" ] && [ -f "$PREFIX/files/runtime-manifest" ]; then
    EXPECTED_ROOTFS_VERSION=$(sed -n 's/^version=//p' "$PREFIX/files/runtime-manifest" | head -n 1)
fi
if [ -z "$(ls -A "$ROOTFS_DIR" | grep -vE '^(root|tmp)$')" ]; then
    ROOTFS_NEEDS_REFRESH=1
elif [ -n "$EXPECTED_ROOTFS_VERSION" ]; then
    INSTALLED_ROOTFS_VERSION=$(cat "$ROOTFS_DIR/etc/omnibot-python-environment" 2>/dev/null || true)
    [ -n "$EXPECTED_ROOTFS_VERSION" ] && [ "$INSTALLED_ROOTFS_VERSION" != "$EXPECTED_ROOTFS_VERSION" ] && ROOTFS_NEEDS_REFRESH=1
fi

if [ "$ROOTFS_NEEDS_REFRESH" = "1" ]; then
    rm -rf "$ROOTFS_STAGING_DIR"
    mkdir -p "$ROOTFS_STAGING_DIR"
    tar -xf "$ROOTFS_ARCHIVE" -C "$ROOTFS_STAGING_DIR" || exit $?
    if [ "$TERMINAL_DISTRIBUTION" = "alpine" ] && [ -n "$EXPECTED_ROOTFS_VERSION" ]; then
        EXTRACTED_ROOTFS_VERSION=$(cat "$ROOTFS_STAGING_DIR/etc/omnibot-python-environment" 2>/dev/null || true)
        [ "$EXTRACTED_ROOTFS_VERSION" = "$EXPECTED_ROOTFS_VERSION" ] || exit 1
    fi
    rm -rf "$ROOTFS_PREVIOUS_DIR"
    mv "$ROOTFS_DIR" "$ROOTFS_PREVIOUS_DIR"
    mv "$ROOTFS_STAGING_DIR" "$ROOTFS_DIR"
    rm -rf "$ROOTFS_PREVIOUS_DIR"
fi

cleanup_rootfs_refresh
trap - 0 1 2 3 15

FIPS_COMPAT_FILE="$PREFIX/local/sysctl_crypto_fips_enabled"
[ ! -f "$FIPS_COMPAT_FILE" ] && {
    mkdir -p "$PREFIX/local"
    printf '0\n' > "$FIPS_COMPAT_FILE"
}

if [ -n "$OMNIBOT_HOST_WORKSPACE" ]; then
    mkdir -p "$OMNIBOT_HOST_WORKSPACE"
    mkdir -p "$ROOTFS_DIR/workspace"
fi

if [ -n "$OMNIBOT_MT_STORAGE_HOST" ] && [ -d "$OMNIBOT_MT_STORAGE_HOST" ]; then
    mkdir -p "$ROOTFS_DIR/mnt/mt" "$ROOTFS_DIR/mt"
fi

mkdir -p "$PREFIX/local/bin" "$PREFIX/local/lib"

install_runtime_file() {
    src="$1"
    dest="$2"
    mode="$3"
    [ -e "$src" ] || return 0
    tmp="${dest}.$$"
    rm -f "$tmp"
    cp "$src" "$tmp" && chmod "$mode" "$tmp" && mv -f "$tmp" "$dest"
}

install_runtime_file "$PREFIX/files/proot" "$PREFIX/local/bin/proot" 755

for sofile in "$PREFIX/files/"*.so.2; do
    [ -e "$sofile" ] || continue
    dest="$PREFIX/local/lib/$(basename "$sofile")"
    install_runtime_file "$sofile" "$dest" 644
done


ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b $PREFIX/local/stat:/proc/stat"
ARGS="$ARGS -b $PREFIX/local/vmstat:/proc/vmstat"
ARGS="$ARGS -b $FIPS_COMPAT_FILE:/proc/.sysctl_crypto_fips_enabled"

if [ -n "$OMNIBOT_HOST_WORKSPACE" ]; then
  ARGS="$ARGS -b $OMNIBOT_HOST_WORKSPACE:/workspace"
fi

if [ -n "$OMNIBOT_MT_STORAGE_HOST" ] && [ -d "$OMNIBOT_MT_STORAGE_HOST" ]; then
  ARGS="$ARGS -b $OMNIBOT_MT_STORAGE_HOST:/mnt/mt"
  ARGS="$ARGS -b $OMNIBOT_MT_STORAGE_HOST:/mt"
fi

if [ "${OMNIBOT_HEADLESS:-0}" != "1" ]; then
  # Interactive PTYs keep these descriptors alive for the whole session. A
  # ProcessBuilder-backed headless command does not: Android may close one of
  # the probed /proc/self/fd entries before PRoot resolves its bind, which makes
  # an otherwise valid runtime bootstrap fail. Headless commands already
  # inherit their pipes through PRoot and the /dev + /proc mounts above.
  if [ -e "/proc/self/fd" ]; then
    ARGS="$ARGS -b /proc/self/fd:/dev/fd"
  fi

  if [ -e "/proc/self/fd/0" ]; then
    ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
  fi

  if [ -e "/proc/self/fd/1" ]; then
    ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
  fi

  if [ -e "/proc/self/fd/2" ]; then
    ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
  fi
fi


ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b /sys"

if [ ! -d "$ROOTFS_DIR/tmp" ]; then
 mkdir -p "$ROOTFS_DIR/tmp"
 chmod 1777 "$ROOTFS_DIR/tmp"
fi
ARGS="$ARGS -b $ROOTFS_DIR/tmp:/dev/shm"

ARGS="$ARGS -r $ROOTFS_DIR"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

$LINKER $PREFIX/local/bin/proot $ARGS /bin/sh $PREFIX/local/bin/init "$@"
