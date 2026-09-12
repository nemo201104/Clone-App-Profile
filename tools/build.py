"""Build with a local JDK 17 and Android SDK; never downloads or publishes implicitly."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import struct
import subprocess
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build'

def run(args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT)

def toolchain():
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'AppData/Local/Android/Sdk'))))
    java_home = os.environ.get('JAVA_HOME')
    if not java_home:
        local = list((ROOT / '.cache/jdk').glob('*/bin/javac.exe'))
        if local:
            java_home = str(local[0].parent.parent)
    suffix = '.exe' if os.name == 'nt' else ''
    java = Path(java_home) / 'bin' / ('java' + suffix) if java_home else Path(shutil.which('java') or 'java')
    javac = Path(java_home) / 'bin' / ('javac' + suffix) if java_home else Path(shutil.which('javac') or 'javac')
    # Avoid silently rebuilding accepted Android binaries with a different compiler.
    for executable in (java, javac):
        version = subprocess.run([str(executable), '-version'], capture_output=True, text=True, check=True)
        import re
        if not re.search(r'(?:version\s+"|javac\s+)17(?:\.|\b)', version.stdout + version.stderr):
            raise SystemExit('JDK 17 is required; set JAVA_HOME to a JDK 17 installation.')
    bt = sdk / 'build-tools/36.0.0'
    platforms = [sdk / 'platforms' / p / 'android.jar' for p in ('android-35', 'android-36', 'android-36.1', 'android-37.0')]
    android = next((p for p in platforms if p.exists()), None)
    if android is None or not bt.exists():
        raise SystemExit('Install Android platform 35 and build-tools 36.0.0; set ANDROID_HOME and JAVA_HOME.')
    return java, javac, bt, android, suffix

def compile_java(javac, sources, output, classpath):
    if output.exists():
        assert output.resolve().is_relative_to(BUILD.resolve()), 'Refusing to clean outside build directory'
        shutil.rmtree(output)
    output.mkdir(parents=True, exist_ok=True)
    # An argument file avoids Windows command-length limits and preserves spaces.
    args = ['--release', '8', '-encoding', 'UTF-8', '-classpath', os.pathsep.join(map(str, classpath)), '-d', str(output)] + [str(p) for p in sources]
    argfile = BUILD / (output.name + '-javac.args')
    argfile.write_text('\n'.join('"' + a.replace('\\', '/').replace('"', '\\"') + '"' for a in args), encoding='utf-8')
    run([javac, '@' + str(argfile)])

def archive_classes(directory, jar):
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in sorted(directory.rglob('*.class')):
            z.write(p, p.relative_to(directory).as_posix())

def stable_entry(name, data, archive, method=zipfile.ZIP_DEFLATED):
    entry=zipfile.ZipInfo(name, (2026,1,1,0,0,0))
    entry.create_system=3
    entry.external_attr=0o100644 << 16
    entry.compress_type=method
    archive.writestr(entry,data)

def dummy_icon():
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    pixels = b''.join(b'\0' + b'\x66\x5c\xe0\xff' * 192 for _ in range(192))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 192, 192, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')

def build():
    java, javac, bt, android, suffix = toolchain()
    BUILD.mkdir(exist_ok=True)
    for name in ('icon.png', 'banner.png'):
        if not (ROOT / 'assets' / name).is_file():
            raise SystemExit('Required branding asset missing: assets/' + name)
    run([java, '-Djava.awt.headless=true', ROOT / 'tools/Branding.java', ROOT / 'assets/icon.png',
         ROOT / 'assets/banner.png', ROOT / 'module/webroot/icon.png'])
    banner = ROOT / 'module/webroot/banner.png'
    shutil.copyfile(ROOT / 'assets/banner.png', banner)
    if banner.read_bytes() != (ROOT / 'assets/banner.png').read_bytes():
        raise SystemExit('Runtime banner differs from assets/banner.png')
    libs = ROOT / 'module/lib'
    libs.mkdir(exist_ok=True)
    # Bundle Google's implementation, not a home-grown APK signature scheme.
    apksig = BUILD / 'apksig.jar'
    with zipfile.ZipFile(bt / 'lib/apksigner.jar') as source, zipfile.ZipFile(apksig, 'w', zipfile.ZIP_DEFLATED) as out:
        for name in source.namelist():
            if name.startswith('com/android/apksig/') and name.endswith('.class'):
                out.writestr(name, source.read(name))
    r8 = bt / 'lib/d8.jar'
    for name in ('proxy', 'core'):
        classes = BUILD / (name + '-classes')
        compile_java(javac, sorted((ROOT / name / 'src').rglob('*.java')), classes, [android, apksig])
        jar = BUILD / (name + '-classes.jar')
        archive_classes(classes, jar)
        dex = BUILD / (name + '-dex')
        dex.mkdir(exist_ok=True)
        inputs = [jar, apksig] if name == 'core' else [jar]
        run([java, '-cp', r8, 'com.android.tools.r8.D8', '--release', '--min-api', '31', '--lib', android, '--output', dex, *inputs])
    icon = ROOT / 'proxy/res/drawable/app_icon.png'
    icon.parent.mkdir(parents=True, exist_ok=True)
    icon.write_bytes(dummy_icon())
    compiled = BUILD / 'proxy-res.zip'
    run([bt / ('aapt2' + suffix), 'compile', '--dir', ROOT / 'proxy/res', '-o', compiled])
    template = BUILD / 'proxy-template.apk'
    run([bt / ('aapt2' + suffix), 'link', '-I', android, '--manifest', ROOT / 'proxy/AndroidManifest.xml', '-o', template, compiled])
    with zipfile.ZipFile(template, 'a', zipfile.ZIP_STORED) as z:
        z.write(BUILD / 'proxy-dex/classes.dex', 'classes.dex')
    with zipfile.ZipFile(template) as source, zipfile.ZipFile(libs / 'proxy-template.apk','w') as out:
        for name in sorted(source.namelist()):
            stable_entry(name,source.read(name),out,source.getinfo(name).compress_type)
    with zipfile.ZipFile(libs / 'core.jar', 'w', zipfile.ZIP_DEFLATED) as z:
        for p in sorted((BUILD / 'core-dex').glob('*.dex')):
            stable_entry(p.name,p.read_bytes(),z)
    # Built binaries are generated outputs, never source-controlled.
    notices = ROOT / 'module/THIRD_PARTY_NOTICES.txt'
    notices.write_text('Includes Android Open Source Project apksig from Android SDK Build Tools 36.0.0.\nCopyright The Android Open Source Project. Licensed under Apache License 2.0.\nhttps://android.googlesource.com/platform/tools/apksig/\n\n' + (ROOT / 'LICENSE').read_text(encoding='utf-8'), encoding='utf-8')
    package()

def package():
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    dest = dist / 'Clone-App-Profile-v1.0.1.zip'
    with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path in sorted((ROOT / 'module').rglob('*')):
            if not path.is_file():
                continue
            relative = path.relative_to(ROOT / 'module').as_posix()
            info = zipfile.ZipInfo(relative, date_time=(2026, 1, 1, 0, 0, 0))
            mode = 0o755 if relative.endswith('.sh') or relative.startswith('bin/') else 0o644
            info.create_system = 3
            info.external_attr = (0o100000 | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, path.read_bytes())
    digest = hashlib.sha256(dest.read_bytes()).hexdigest()
    (dist / (dest.name + '.sha256')).write_text(digest + '  ' + dest.name + '\n', encoding='ascii')
    shutil.copy2(ROOT / 'update.json', dist / 'update.json')
    print(dest)
    print('SHA256:', digest)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--package-only', action='store_true')
    args = parser.parse_args()
    package() if args.package_only else build()
