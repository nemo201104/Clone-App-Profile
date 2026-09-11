package io.github.nemo.cap;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Rewrites only strings in our own compiled manifest; never edits a system APK. */
public final class BinaryXml {
    private static int u16(byte[] b,int at) {return (b[at]&255)|((b[at+1]&255)<<8);}
    private static int i32(byte[] b,int at) {return ByteBuffer.wrap(b,at,4).order(ByteOrder.LITTLE_ENDIAN).getInt();}
    private static void put(byte[] b,int at,int v) {ByteBuffer.wrap(b,at,4).order(ByteOrder.LITTLE_ENDIAN).putInt(v);}
    private static int length(byte[] b,int[] at,boolean utf8) throws IOException {
        int v=utf8?b[at[0]++]&255:u16(b,at[0]);if(!utf8)at[0]+=2;
        int mask=utf8?128:32768;
        if((v&mask)!=0) {int next=utf8?b[at[0]++]&255:u16(b,at[0]);if(!utf8)at[0]+=2;v=((v&~mask)<<(utf8?8:16))|next;}
        return v;
    }
    private static void len8(ByteArrayOutputStream b,int n) throws IOException {
        if(n>32767)throw new IOException("Manifest string too long");
        if(n>127)b.write((n>>8)|128);b.write(n&255);
    }
    public static byte[] rewrite(byte[] xml,Map<String,String> replacements) throws IOException {
        if(xml.length<36 || u16(xml,0)!=3 || i32(xml,4)!=xml.length)throw new IOException("Invalid binary XML");
        int pos=u16(xml,2);
        while(pos+8<=xml.length && u16(xml,pos)!=1) {int n=i32(xml,pos+4);if(n<8)throw new IOException("Invalid chunk");pos+=n;}
        if(pos+28>xml.length)throw new IOException("Missing string pool");
        int size=i32(xml,pos+4),count=i32(xml,pos+8),styles=i32(xml,pos+12),flags=i32(xml,pos+16),start=i32(xml,pos+20);
        if(styles!=0 || count<0 || count>10000 || pos+size>xml.length)throw new IOException("Unsupported string pool");
        ByteArrayOutputStream strings=new ByteArrayOutputStream();int[] offsets=new int[count];
        for(int i=0;i<count;i++) {
            int[] at={pos+start+i32(xml,pos+28+i*4)};boolean utf8=(flags&256)!=0;
            int chars=length(xml,at,utf8),bytes=utf8?length(xml,at,true):chars*2;
            String s=new String(xml,at[0],bytes,utf8?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE);
            s=replacements.getOrDefault(s,s);byte[] encoded=s.getBytes(StandardCharsets.UTF_8);offsets[i]=strings.size();
            len8(strings,s.length());len8(strings,encoded.length);strings.write(encoded);strings.write(0);
        }
        while(strings.size()%4!=0)strings.write(0);
        byte[] pool=new byte[28+4*count+strings.size()];pool[0]=1;pool[2]=28;
        put(pool,4,pool.length);put(pool,8,count);put(pool,16,256);put(pool,20,28+4*count);
        for(int i=0;i<count;i++)put(pool,28+i*4,offsets[i]);
        System.arraycopy(strings.toByteArray(),0,pool,28+4*count,strings.size());
        ByteArrayOutputStream result=new ByteArrayOutputStream();result.write(xml,0,pos);result.write(pool);result.write(xml,pos+size,xml.length-pos-size);
        byte[] out=result.toByteArray();put(out,4,out.length);return out;
    }
}
