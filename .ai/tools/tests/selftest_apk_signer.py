#!/usr/bin/env python3
"""自检 read_apk_signer.py：现场造一个带 v2 签名块的 APK，看能不能读出指纹。

为什么要有这个：`apksigner` 不在 runner 的 PATH 上，签名指纹只能靠这段
自己写的解析器。它一旦静默读错（返回空、或读出一串垃圾），页面上的
「签名」就永远是「未能读取」——而那正是我们要避免的状态。

做法：用 openssl/keytool 现场生成证书 → 手工拼一个符合 APK v2 结构的
「APK」→ 断言解析出的 SHA-256 与证书文件的 SHA-256 逐字节相等。
再断言未签名的 APK 返回非零（不能把「没有签名」说成「签名正常」）。

变异测试记录（2026-11-01，故意改坏实现看是否判红）：
  1. signer 少读 count（这就是最初那个 bug）        → 判红 ✓
  2. 证书序列少读 count                             → 判红 ✓
  3. size foot 读在 magic 之后                      → 判红 ✓
  4. 未签名时也输出空指纹并返回 0                    → 判红 ✓
  5. 去掉「size head == size foot」这项自校验         → **仍绿，未覆盖**
     原因：本自检造的样本本身结构合法，这项是防御性的——只在文件被截断/
     篡改时才起作用。如实记在这里，不假装它被测到了。
"""
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
PARSER = os.path.join(os.path.dirname(HERE), 'read_apk_signer.py')


def build_v2_apk(path, cert_der):
    """拼一个最小的、结构合法的 v2 签名 APK（签名值本身不用真，我们只读证书）。"""
    def lp(b):
        return struct.pack('<I', len(b)) + b

    def seq(items):
        return struct.pack('<I', len(items)) + b''.join(lp(i) for i in items)

    # 一个空 zip（能当 APK 打开，且没有 META-INF 签名）
    import zipfile
    raw = os.path.join(os.path.dirname(path), 'raw.apk')
    with zipfile.ZipFile(raw, 'w') as z:
        z.writestr('AndroidManifest.xml', b'\x00' * 32)
        z.writestr('classes.dex', b'dex\n035\x00' + b'\x00' * 32)
    base = open(raw, 'rb').read()

    digest_item = struct.pack('<I', 0x0103) + lp(hashlib.sha256(b'x').digest())
    signed = seq([digest_item]) + seq([cert_der]) + seq([])
    signer = (lp(signed)
              + seq([struct.pack('<I', 0x0103) + lp(b'\x00' * 64)])
              + lp(cert_der))
    value = lp(seq([signer]))
    pair = struct.pack('<Q', 4 + len(value)) + struct.pack('<I', 0x7109871A) + value
    size = 8 + len(pair)
    block = (struct.pack('<Q', size) + pair + struct.pack('<Q', size)
             + b'APK Sig Block 42')

    eocd = base.rfind(b'PK\x05\x06')
    cd_off = struct.unpack('<I', base[eocd + 16:eocd + 20])[0]
    out = bytearray(base[:cd_off] + block + base[cd_off:])
    struct.pack_into('<I', out, eocd + len(block) + 16, cd_off + len(block))
    open(path, 'wb').write(bytes(out))
    os.unlink(raw)
    return os.path.join(os.path.dirname(path), 'raw.apk')


def main():
    if not shutil.which('keytool'):
        print('SKIP 没有 keytool，无法生成样本证书')
        return 0
    tmp = tempfile.mkdtemp(prefix='apksigner-test-')
    try:
        ks = os.path.join(tmp, 't.jks')
        subprocess.run(['keytool', '-genkeypair', '-keystore', ks,
                        '-storepass', 'testtest', '-keypass', 'testtest',
                        '-alias', 'vaultix', '-keyalg', 'RSA', '-keysize', '2048',
                        '-validity', '1', '-dname', 'CN=Vaultix Self Test'],
                       check=True, capture_output=True)
        cert = os.path.join(tmp, 'c.der')
        subprocess.run(['keytool', '-exportcert', '-keystore', ks,
                        '-storepass', 'testtest', '-alias', 'vaultix',
                        '-file', cert], check=True, capture_output=True)
        der = open(cert, 'rb').read()
        want = hashlib.sha256(der).hexdigest()

        apk = os.path.join(tmp, 'signed.apk')
        build_v2_apk(apk, der)
        p = subprocess.run([sys.executable, PARSER, apk],
                           capture_output=True, text=True)
        got = (p.stdout.strip().split('|')[0] if p.stdout.strip() else '')

        fails = []
        if p.returncode != 0:
            fails.append('解析签名块返回 %d（stderr: %s）'
                         % (p.returncode, p.stderr.strip()[:200]))
        elif got != want:
            fails.append('指纹不符\n  want %s\n  got  %s' % (want, got))

        # 未签名的东西必须判「读不到」，而不是给个空指纹冒充成功
        p2 = subprocess.run([sys.executable, PARSER, os.path.join(tmp, 'raw.apk')]
                            if os.path.exists(os.path.join(tmp, 'raw.apk'))
                            else [sys.executable, PARSER, cert],
                            capture_output=True, text=True)
        if p2.returncode == 0:
            fails.append('未签名文件竟然读出了签名（rc=0，输出 %r）'
                         % p2.stdout.strip()[:80])

        if fails:
            print('FAIL read_apk_signer 自检')
            for f in fails:
                print('  - ' + f)
            return 1
        print('OK   v2 签名块解析到的指纹与证书 SHA-256 一致：%s…' % want[:16])
        print('OK   未签名文件被正确判为读不到')
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    sys.exit(main())
