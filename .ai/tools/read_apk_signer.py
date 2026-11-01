#!/usr/bin/env python3
"""从 APK 里读签名证书指纹（v2/v3 签名块，兜底 v1 META-INF）。

用法：read_apk_signer.py <apk>

输出两行（`|` 分隔）：
    <SHA-256>|<subject DN>
读不到时输出空并返回非零。

为什么要这个脚本：`apksigner` 不在 GitHub runner 的 PATH 上，而 debug 包
用 v2 签名，证书不在 `META-INF/*.RSA`（v1）里，而在 APK Signing Block
里。读不到就一直显示「未能读取」——那等于没做。
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
    """读一个 uint32 长度前缀（APK 的 length-prefixed）。返回 (payload, next_off)。"""
    if off + 4 > len(buf):
        raise ValueError('LP 长度字段越界')
    n = struct.unpack('<I', buf[off:off + 4])[0]
    start = off + 4
    end = start + n
    if end > len(buf):
        raise ValueError('LP 内容越界（%d > %d）' % (end, len(buf)))
    return buf[start:end], end


def _seq_first(buf, off, what):
    """读一个序列的第一项：先读 count(uint32)，再跳过 4 字节读第一项的 LP。

    APK 的每个「列表」都是 count + LP(item)*。少读这个 count 就整体偏 4
    字节——读出来的是垃圾，而且不报错。
    """
    if off + 8 > len(buf):
        raise ValueError('%s：序列头越界' % what)
    count = struct.unpack('<I', buf[off:off + 4])[0]
    if count < 1:
        raise ValueError('%s：序列为空' % what)
    item, _ = _lp(buf, off + 4)
    return item, count


def _first_cert(signed):
    """signed data = digests(序列) + certificates(序列) + attrs(序列)。"""
    # digests：只用来跳过，不读取
    if len(signed) < 4:
        raise ValueError('signed data 过短')
    digests_count = struct.unpack('<I', signed[:4])[0]
    off = 4
    for _ in range(digests_count):
        _, off = _lp(signed, off)
    cert, _ = _seq_first(signed, off, 'certificates')
    return cert


def v2_cert(apk_path):
    with open(apk_path, 'rb') as f:
        data = f.read()
    i = data.find(MAGIC)
    if i < 0:
        return None
    # 布局：<size head(8)><pairs...><size foot(8)><magic(16)>
    # size foot 紧贴 magic 之前，且等于块总长；块首的 size head 与之相等。
    if i < 8:
        return None
    size = struct.unpack('<Q', data[i - 8:i])[0]
    start = i - 8 - size
    if start < 0 or start + 8 > len(data):
        return None
    if struct.unpack('<Q', data[start:start + 8])[0] != size:
        return None
    off = start + 8
    end = i - 8               # pairs 区结束于 size foot
    while off + 12 <= end:
        plen = struct.unpack('<Q', data[off:off + 8])[0]
        pid = struct.unpack('<I', data[off + 8:off + 12])[0]
        if plen < 4:
            return None
        value = data[off + 12:off + 8 + plen]
        if pid in (V2_ID, V3_ID):
            try:
                signers, _ = _lp(value, 0)          # value = LP(signers 序列)
                signer, _ = _seq_first(signers, 0, 'signers')
                signed, _ = _lp(signer, 0)          # signed data
                return _first_cert(signed)
            except ValueError:
                pass
        off += 8 + plen
    return None


def v1_cert(apk_path):
    """v1 签名：META-INF/*.(RSA|DSA|EC) 里就是 PKCS#7，取第一张证书。"""
    try:
        with zipfile.ZipFile(apk_path) as z:
            for n in z.namelist():
                if n.startswith('META-INF/') and n.upper().endswith(('.RSA', '.DSA', '.EC')):
                    blob = z.read(n)
                    # PKCS#7 里证书是 DER；粗暴找第一个 SEQUENCE 30 82
                    k = blob.find(b'\x30\x82')
                    if k >= 0:
                        ln = struct.unpack('>H', blob[k + 2:k + 4])[0] + 4
                        return blob[k:k + ln]
    except (zipfile.BadZipFile, OSError):
        pass
    return None


def dn_of(cert):
    """用 keytool 把 DER 转成可读主语；失败返回空串。"""
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
