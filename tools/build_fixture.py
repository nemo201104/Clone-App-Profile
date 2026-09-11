"""Build an isolated, permission-free test app. Never included in the module ZIP."""
from pathlib import Path
import os
import sys
import zipfile
sys.path.insert(0,str(Path(__file__).parent))
from build import ROOT, BUILD, toolchain, compile_java, archive_classes, run

def main():
    java,javac,bt,android,suffix=toolchain()
    cls=BUILD/'fixture-classes'
    compile_java(javac,sorted((ROOT/'tests/fixture/src').rglob('*.java')),cls,[android])
    jar=BUILD/'fixture-classes.jar';archive_classes(cls,jar)
    dex=BUILD/'fixture-dex';dex.mkdir(exist_ok=True)
    run([java,'-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','31','--lib',android,'--output',dex,jar])
    unsigned=BUILD/'fixture-unsigned.apk'
    run([bt/('aapt2'+suffix),'link','-I',android,'--manifest',ROOT/'tests/fixture/AndroidManifest.xml','-o',unsigned,BUILD/'proxy-res.zip'])
    with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_STORED) as z:z.write(dex/'classes.dex','classes.dex')
    aligned=BUILD/'fixture-aligned.apk';run([bt/('zipalign'+suffix),'-f','4',unsigned,aligned])
    keys=ROOT/'.local';keys.mkdir(exist_ok=True);keystore=keys/'fixture.p12'
    if not keystore.exists():
        keytool=java.parent/('keytool'+suffix)
        run([keytool,'-genkeypair','-keystore',keystore,'-storepass','cap-fixture-only','-keypass','cap-fixture-only','-alias','fixture','-keyalg','RSA','-keysize','3072','-validity','3650','-dname','CN=CAP disposable test fixture'])
    signed=BUILD/'cap-fixture.apk'
    run([java,'-jar',bt/'lib/apksigner.jar','sign','--ks',keystore,'--ks-pass','pass:cap-fixture-only','--out',signed,aligned])
    print(signed)

if __name__=='__main__':main()
