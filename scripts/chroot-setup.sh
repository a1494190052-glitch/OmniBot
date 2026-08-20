#!/bin/sh
# chroot-setup.sh — 在 OmniBot 内准备一个可 chroot 的 Linux rootfs。
#
# 用法: scripts/chroot-setup.sh <目标目录> [alpine|debian]
# 示例: scripts/chroot-setup.sh /workspace/rootfs/alpine alpine
#       scripts/chroot-setup.sh /workspace/rootfs/debian debian
#
# 说明:
#   - 在 proot 终端环境内直接运行即可（terminal_execute）。
#   - 生成的目录可作为 Agent 工具 terminal_chroot 的 rootfsPath。
#   - Alpine 走官方 minirootfs 直链（可靠）；Debian 需要 debootstrap。
set -eu

TARGET="${1:?用法: chroot-setup.sh <目标目录> [alpine|debian]}"
DISTRO="${2:-alpine}"

case "$DISTRO" in
  alpine)
    VER="3.20.3"
    ARCH="aarch64"
    URL="https://dl-cdn.alpinelinux.org/alpine/v${VER%.*}/releases/${ARCH}/alpine-minirootfs-${VER}-${ARCH}.tar.gz"
    ;;
  debian)
    if command -v debootstrap >/dev/null 2>&1; then
      echo "==> 使用 debootstrap 构建 Debian (stable/arm64) rootfs"
      mkdir -p "$TARGET"
      debootstrap --arch=arm64 stable "$TARGET" http://deb.debian.org/debian
      echo "==> 完成。rootfs: $TARGET"
      exit 0
    fi
    echo "==> 未找到 debootstrap，请先安装：apk add debootstrap" >&2
    exit 2
    ;;
  *)
    echo "未知发行版: $DISTRO（支持 alpine / debian）" >&2
    exit 1
    ;;
esac

mkdir -p "$TARGET"
echo "==> 下载 $DISTRO rootfs: $URL"
if command -v wget >/dev/null 2>&1; then
  wget -q -O /tmp/chroot-rootfs.tar.gz "$URL"
else
  curl -fsSL -o /tmp/chroot-rootfs.tar.gz "$URL"
fi
echo "==> 解压到 $TARGET"
tar -xzf /tmp/chroot-rootfs.tar.gz -C "$TARGET"
rm -f /tmp/chroot-rootfs.tar.gz
echo "==> 完成。rootfs: $TARGET"
echo "    现在可用 terminal_chroot: rootfsPath=$TARGET, command='echo hello'"
