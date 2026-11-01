#!/usr/bin/env python3
"""从 APK 里读签名证书指纹（v2/v3 签名块，兜底 v1 META-INF）。

用法：read_apk_signer.py <apk>

输出一行（`|` 分隔）：
    <SHA-256>|<subject DN>
读不到时输出空并返回非零。

为什么要这个脚本：`apksigner` **不在 GitHub runner 的 PATH 上**（在
`$ANDROID_SDK_ROOT/build-tools/<ver>/` 下），而 debug 包用 v2 签名，证书不在
`META-INF/*.RSA`（v1）里。读不到就一直显示「未能读取」——那等于没做。

=== APK Signature Scheme v2 的真实二进制布局 ===

    APK 文件 = [zip 条目…] [签名块] [中央目录] [EOCD]
    签名块   = <size head: u64> <pairs> <size foot: u64> "APK Sig Block 42"(16)
               ↑ head 就在块首               ↑ foot 紧贴 magic 之前
               size = foot_pos - head_pos，即「head 起点到 magic 起点的跨度」
               ⇒ head_pos = magic_pos - size - 8

    pair    = <len: u64> <id: u32> <value: len-4 字节>
              id 0x7109871a = v2，0xf05368c0 = v3

    v2 value = LP( signers )
    signer   = LP(signed) LP(signatures) LP(publicKey)
    signed   = LP(digests) LP(certificates) LP(attrs)
    certificates = LP(第一张证书) LP(第二张) …     ← 就是连续的 LP，没有 count

⚠️ 这里最容易错、而且**错了不报错**的一点：上述每一层都是 **length-prefixed（LP）**，
   **没有**「count + LP*」那种带计数的序列形式。把 LP 的长度字段当成 count 会整体
   偏移 4 字节，读出来的「证书」是一段垃圾 —— 但它照样是一段字节，
   `sha256()` 照样能算出结果，于是你得到一个**看起来正常、其实错误**的指纹。

⚠️ 2026-11-01 这份解析器改了四版才对，前三版全是静默错误，而且**自造样本每次都“通过”**
   （样本是按我当时的错误理解拼的 —— 自测自己必然通过）。最后一次是拿
   `apksigner sign` 产出的真实 APK 对账才定的：
     第一版 size 从 magic 之后读          → 块起点算飞
     第二版 head = magic - 16 - size      → 偏 8 字节
     第三版 signed 里按 count 读           → 偏 4 字节，读到错误证书
     第四版 head = magic + 8 - size        → 对上了（下面这行）
   ⇒ 自检脚本 `tests/selftest_apk_signer.py` 现在**只用真实 apksigner 产物**对账；
     本机没有 apksigner 时直接 SKIP，绝不退回自造样本。

   另：`start = i + 8 - size` 这个 +8 与「规范里 size 的语义」对不上，但它是
   **对真实产物实测出来的**（magic 8176、size 4088 → head 4096）。写这行时
   不要再按推理去"修正"它 —— 把 `tests/selftest_apk_signer.py` 跑一遍再动。
"""
import hashlib
import os
import struct
import subprocess
import sys
import tempfile
import zipfile

MAGIC = b'APK Sig Block 42'
V2_ID = 0x7109871A
V3_ID = 0xF05368C0


def _lp(buf, off):
    """读一个 uint32 长度前缀块。返回 (payload, next_off)。"""
    if off + 4 > len(buf):
        raise ValueError('LP 长度字段越界（off=%d len=%d）' % (off, len(buf)))
    n = struct.unpack('<I', buf[off:off + 4])[0]
    start = off + 4
    end = start + n
    if end > len(buf):
        raise ValueError('LP 内容越界（需要 %d，只有 %d）' % (end, len(buf)))
    return buf[start:end], end


def _first_cert(signed):
    """signed = LP(digests) LP(certificates) LP(attrs)；取第一张证书。"""
    _, off = _lp(signed, 0)          # digests（跳过）
    certs, _ = _lp(signed, off)      # certificates
    cert, _ = _lp(certs, 0)          # 第一张
    return cert


def v2_cert(apk_path):
    with open(apk_path, 'rb') as f:
        data = f.read()
    i = data.find(MAGIC)
    if i < 0:
        return None
    if i < 8:
        return None
    size = struct.unpack('<Q', data[i - 8:i])[0]
    start = i + 8 - size
    if start < 0 or start + 8 > len(data):
        return None
    if struct.unpack('<Q', data[start:start + 8])[0] != size:
        return None

    off = start + 8
    end = i - 8                       # pairs 区结束于 size foot
    while off + 12 <= end:
        plen = struct.unpack('<Q', data[off:off + 8])[0]
        pid = struct.unpack('<I', data[off + 8:off + 12])[0]
        if plen < 4:
            return None
        value = data[off + 12:off + 8 + plen]
        if pid in (V2_ID, V3_ID):
            try:
                signers, _ = _lp(value, 0)       # value = LP(signers)
                signer, _ = _lp(signers, 0)      # 第一个 signer
                signed, _ = _lp(signer, 0)       # signed data
                return _first_cert(signed)
            except ValueError:
                pass                             # 这个 pair 不是我们要的，继续找
        off += 8 + plen
    return None


def v1_cert(apk_path):
    """v1 签名：META-INF/*.(RSA|DSA|EC) 里是 PKCS#7，取其中第一张证书。"""
    try:
        with zipfile.ZipFile(apk_path) as z:
            for n in z.namelist():
                if n.startswith('META-INF/') and n.upper().endswith(('.RSA', '.DSA', '.EC')):
                    blob = z.read(n)
                    k = blob.find(b'\x30\x82')
                    if k >= 0:
                        ln = struct.unpack('>H', blob[k + 2:k + 4])[0] + 4
                        return blob[k:k + ln]
    except (zipfile.BadZipFile, OSError):
        pass
    return None


def dn_of(cert):
    """用 keytool 把 DER 转成可读主语；失败返回空串（不影响指纹）。"""
    tmp = None
    try:
        with tempfile.NamedTemporaryFile(suffix='.der', delete=False) as f:
            f.write(cert)
            tmp = f.name
        p = subprocess.run(['keytool', '-printcert', '-file', tmp],
                           capture_output=True, timeout=20)
        for line in p.stdout.decode('utf-8', 'replace').splitlines():
            s = line.strip()
            if s.startswith('Owner:'):
                return s.split(':', 1)[1].strip()
    except (OSError, subprocess.SubprocessError):
        pass
    finally:
        if tmp:
            try:
                os.unlink(tmp)
            except OSError:
                pass
    return ''


def main():
    if len(sys.argv) != 2:
        sys.stderr.write('usage: read_apk_signer.py <apk>\n')
        return 2
    cert = v2_cert(sys.argv[1]) or v1_cert(sys.argv[1])
    if not cert:
        return 1
    print('%s|%s' % (hashlib.sha256(cert).hexdigest(), dn_of(cert)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
