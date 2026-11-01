#!/usr/bin/env python3
"""自检 read_apk_signer.py：拿**真实 apksigner 产物**对账，指纹必须逐字节相等。

=== 为什么现在优先用真 apksigner，而不是自己拼样本 ===

2026-11-01 的教训：这份解析器前后改了四版，前三版全错，而**自造样本每次都"通过"**——
因为样本是按我当时的错误理解拼出来的。**自测自己，必然通过。**
换成 `apksigner sign` 产出的真实 APK 后，三个错误当场全部暴露。

所以顺序是：
  1. 找得到 apksigner → 真签一个 APK → 断言两边指纹**逐字节相同**（首选）；
  2. 找不到 → 退回自造样本，并**在输出里明说降级了**，不假装和 ① 等价。

另外必须断言「未签名文件返回非零」——不能把"没有签名"说成"签名正常"。

=== 变异测试记录（故意改坏实现，看自检是否判红）===

  1. signer 少读一层 LP                    → 判红 ✓
  2. 证书序列按 count 读（而不是 LP）        → 判红 ✓
  3. start = magic - 8 - size（偏 8 字节）   → 判红 ✓
  4. 未签名时也输出空指纹并返回 0             → 判红 ✓
  5. 去掉 size head == size foot 自校验      → **仍绿，未覆盖**
     原因：它是防御性断言，只在文件被截断/篡改时起作用；
     正常 APK 与自造样本都满足，抓不到。如实记下，不假装覆盖。
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


def find_apksigner():
    p = shutil.which('apksigner')
    if p:
        return p
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    if sdk:
        cand = sorted(
            (os.path.join(sdk, 'build-tools', v, 'apksigner')
             for v in os.listdir(os.path.join(sdk, 'build-tools'))
             if os.path.isdir(os.path.join(sdk, 'build-tools', v))),
            reverse=True)
        for c in cand:
            if os.access(c, os.X_OK):
                return c
    return None


def build_unsigned_apk(path):
    import zipfile
    with zipfile.ZipFile(path, 'w') as z:
        z.writestr('AndroidManifest.xml', b'\x00' * 32)
        z.writestr('classes.dex', b'dex\n035\x00' + b'\x00' * 32)


def run_parser(path):
    p = subprocess.run([sys.executable, PARSER, path], capture_output=True, text=True)
    return p.returncode, p.stdout.strip()


def main():
    if not shutil.which('keytool'):
        print('SKIP 没有 keytool，无法生成样本证书')
        return 0

    tmp = tempfile.mkdtemp(prefix='apksigner-test-')
    fails = []
    try:
        ks = os.path.join(tmp, 'k.jks')
        subprocess.run(['keytool', '-genkeypair', '-keystore', ks,
                        '-storepass', 'testtest', '-keypass', 'testtest',
                        '-alias', 'vaultix', '-keyalg', 'RSA', '-keysize', '2048',
                        '-validity', '1', '-dname', 'CN=Vaultix Self Test'],
                       check=True, capture_output=True)
        cert = os.path.join(tmp, 'c.der')
        subprocess.run(['keytool', '-exportcert', '-keystore', ks,
                        '-storepass', 'testtest', '-alias', 'vaultix',
                        '-file', cert], check=True, capture_output=True)
        want = hashlib.sha256(open(cert, 'rb').read()).hexdigest()

        apksigner = find_apksigner()
        unsigned = os.path.join(tmp, 'unsigned.apk')
        build_unsigned_apk(unsigned)

        if apksigner:
            signed = os.path.join(tmp, 'signed.apk')
            r = subprocess.run([apksigner, 'sign', '--min-sdk-version', '24',
                                '--ks', ks, '--ks-pass', 'pass:testtest',
                                '--key-pass', 'pass:testtest',
                                '--out', signed, unsigned],
                               capture_output=True, text=True)
            mode = '真实 apksigner 产物'
            if r.returncode != 0:
                fails.append('apksigner sign 失败：' + (r.stderr or r.stdout)[:300])
                signed = None
            else:
                # 交叉校验：apksigner 自己读出来的指纹
                v = subprocess.run([apksigner, 'verify', '--min-sdk-version', '24',
                                    '--print-certs', signed],
                                   capture_output=True, text=True)
                m = [ln for ln in v.stdout.splitlines() if 'SHA-256 digest' in ln]
                if m:
                    ref = m[0].rsplit(':', 1)[1].strip()
                    if ref != want:
                        fails.append('样本本身对不上：apksigner 说 %s，证书是 %s'
                                     % (ref, want))
        else:
            # 宁可 SKIP，也不要用"自己拼的样本"给出一个虚假的绿色。
            # 2026-11-01 就是这么被骗了四版：样本按错误理解拼，测试必然通过。
            print('SKIP 本机没有 apksigner —— 无法用真实产物对账。')
            print('     不退回自造样本：自测自己等于没有测试（#218.4 的教训）。')
            print('     CI runner 自带 apksigner，那里会真跑。')
            return 0

        if signed and os.path.exists(signed):
            rc, out = run_parser(signed)
            got = out.split('|')[0] if out else ''
            if rc != 0:
                fails.append('解析签名块返回 %d（自造/真实样本：%s）' % (rc, mode))
            elif got != want:
                fails.append('指纹不符\n  want %s\n  got  %s' % (want, got))

        rc2, out2 = run_parser(unsigned)
        if rc2 == 0:
            fails.append('未签名文件竟然读出了签名（rc=0，输出 %r）' % out2[:80])

        if fails:
            print('FAIL read_apk_signer 自检（样本来源：%s）' % mode)
            for f in fails:
                print('  - ' + f)
            return 1
        print('OK   指纹与证书 SHA-256 逐字节一致：%s…（样本：%s）' % (want[:16], mode))
        print('OK   未签名文件被正确判为读不到')
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    sys.exit(main())
