package io.github.nemo.cap;

import com.android.apksig.ApkSigner;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.*;
import java.math.BigInteger;
import java.util.*;
import java.util.zip.*;

public final class ProxyApk {
    public static final String PREFIX="io.github.nemo.cap.entry.p";
    public static final String ACTIVITY="io.github.nemo.cap.proxy.EntryActivity";
    private final File dir,module;
    public ProxyApk(File dir,File module) {this.dir=dir;this.module=module;}
    private static byte[] der(int tag,byte[]... contents) throws IOException {
        ByteArrayOutputStream body=new ByteArrayOutputStream();for(byte[] b:contents)body.write(b);
        int n=body.size();ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(tag);
        if(n<128)out.write(n);else {int bytes=n>65535?3:n>255?2:1;out.write(128|bytes);for(int i=bytes-1;i>=0;i--)out.write((n>>(i*8))&255);}
        out.write(body.toByteArray());return out.toByteArray();
    }
    private static byte[] seq(byte[]... v) throws IOException {return der(48,v);}
    private JSONObject signing() throws Exception {
        File f=new File(dir,"signing.json");
        if(f.exists())return new JSONObject(Store.read(f));
        // Never rotate a lost signing identity over existing registered proxies.
        JSONObject registry=new JSONObject(Store.read(new File(dir,"registry.json")));
        JSONArray clones=registry.getJSONArray("clones");
        for(int i=0;i<clones.length();i++)Failure.require(!clones.getJSONObject(i).has("proxyCertificate"),"SIGNING_KEY_MISSING","Restore signing.json from your private backup");
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);KeyPair pair=generator.generateKeyPair();
        byte[] algorithm=seq(der(6,new byte[]{42,(byte)134,72,(byte)134,(byte)247,13,1,1,11}),der(5,new byte[0]));
        byte[] name=seq(der(49,seq(der(6,new byte[]{85,4,3}),der(12,"Clone App Profile device key".getBytes(StandardCharsets.UTF_8)))));
        byte[] tbs=seq(der(160,der(2,new byte[]{2})),der(2,new BigInteger(128,new SecureRandom()).toByteArray()),algorithm,name,
            seq(der(24,"20200101000000Z".getBytes(StandardCharsets.US_ASCII)),der(24,"21200101000000Z".getBytes(StandardCharsets.US_ASCII))),name,pair.getPublic().getEncoded());
        java.security.Signature signature=java.security.Signature.getInstance("SHA256withRSA");signature.initSign(pair.getPrivate());signature.update(tbs);
        byte[] cert=seq(tbs,algorithm,der(3,new byte[]{0},signature.sign()));
        CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
        JSONObject value=Json.obj("privateKey",Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),"certificate",Base64.getEncoder().encodeToString(cert));
        Store.atomic(f,value.toString().getBytes(StandardCharsets.UTF_8));return value;
    }
    public String certificate() throws Exception {return Json.hash(Base64.getDecoder().decode(signing().getString("certificate")));}
    public File build(String id,String label,byte[] icon) throws Exception {
        Failure.require(id.matches("[a-f0-9]{64}"),"INVALID_ARGUMENT","Invalid proxy identity");
        JSONObject key=signing();
        File work=new File(dir,"apks");java.nio.file.Files.createDirectories(work.toPath());
        File unsigned=new File(work,id+".unsigned.apk"),signed=new File(work,id+".apk");
        Map<String,String> replace=new HashMap<>();replace.put("io.github.nemo.cap.template",PREFIX+id);replace.put("CAP_PROXY_LABEL",label);
        try(ZipFile template=new ZipFile(new File(module,"lib/proxy-template.apk"));Counting out=new Counting(new FileOutputStream(unsigned));ZipOutputStream zip=new ZipOutputStream(out)) {
            Enumeration<? extends ZipEntry> entries=template.entries();
            while(entries.hasMoreElements()) {
                ZipEntry original=entries.nextElement();String name=original.getName();
                if(original.isDirectory() || name.startsWith("META-INF/"))continue;
                byte[] bytes;
                try(InputStream in=template.getInputStream(original)) {ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] part=new byte[8192];int n;while((n=in.read(part))!=-1)buffer.write(part,0,n);bytes=buffer.toByteArray();}
                if(name.equals("AndroidManifest.xml"))bytes=BinaryXml.rewrite(bytes,replace);
                if(name.equals("res/drawable/app_icon.png"))bytes=icon;
                ZipEntry e=new ZipEntry(name);e.setTime(315532800000L);e.setMethod(ZipEntry.STORED);e.setSize(bytes.length);e.setCompressedSize(bytes.length);
                CRC32 crc=new CRC32();crc.update(bytes);e.setCrc(crc.getValue());
                int pad=(int)((4-(out.count+30+name.getBytes(StandardCharsets.UTF_8).length+4)%4)%4);
                byte[] extra=new byte[4+pad];extra[0]=0x35;extra[1]=(byte)0xd9;extra[2]=(byte)pad;e.setExtra(extra);
                zip.putNextEntry(e);zip.write(bytes);zip.closeEntry();
            }
        }
        PrivateKey privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.getString("privateKey"))));
        X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(key.getString("certificate"))));
        ApkSigner.SignerConfig config=new ApkSigner.SignerConfig.Builder("cap",privateKey,Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(config)).setInputApk(unsigned).setOutputApk(signed)
            .setMinSdkVersion(31).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(false).setV4SigningEnabled(false).build().sign();
        java.nio.file.Files.delete(unsigned.toPath());return signed;
    }
    private static final class Counting extends FilterOutputStream {
        long count;Counting(OutputStream out){super(out);}
        public void write(int b)throws IOException {out.write(b);count++;}
        public void write(byte[] b,int off,int len)throws IOException {out.write(b,off,len);count+=len;}
    }
}
