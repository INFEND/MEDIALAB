package com.xarkon.medialab;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TestResumableModuleDownload {
    private static final byte[] DATA=new byte[500000];
    private static final String URL="https://test.invalid/module.so";
    private static final List<String> ranges=new ArrayList<>();
    private static int mode=0,requests=0;
    static {new Random(27).nextBytes(DATA);}
    static final class Mock extends HttpURLConnection {
        Mock(java.net.URL url){super(url);}
        int start,code;
        byte[] payload;
        String range;
        @Override public void connect(){}
        @Override public void disconnect(){}
        @Override public boolean usingProxy(){return false;}
        @Override public int getResponseCode(){
            String req=getRequestProperty("Range");
            ranges.add(req==null?"full":req);
            requests++;
            start=req==null?0:Integer.parseInt(req.substring(6,req.length()-1));
            if(mode==2&&start>0){code=200;start=0;}
            else if(mode==3&&start>0){code=416;return code;}
            else code=start==0?200:206;
            int end=mode==1&&requests==1?200000:DATA.length;
            payload=Arrays.copyOfRange(DATA,start,end);
            range="bytes "+start+"-"+(DATA.length-1)+"/"+DATA.length;
            return code;
        }
        @Override public long getContentLengthLong(){return DATA.length-start;}
        @Override public String getHeaderField(String field){
            if(field.equalsIgnoreCase("Content-Range"))return code==206?range:null;
            if(field.equalsIgnoreCase("ETag"))return "\"test-v1\"";
            return null;
        }
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(payload);}
    }
    private static void seed(File part,int count)throws Exception{
        Files.write(part.toPath(),Arrays.copyOf(DATA,count));
        Properties p=new Properties();p.setProperty("url",URL);p.setProperty("total",Integer.toString(DATA.length));
        p.setProperty("validator","\"test-v1\"");
        try(FileOutputStream o=new FileOutputStream(part.getPath()+".meta")){p.store(o,null);}
    }
    private static void verify(File f)throws Exception{
        if(!Arrays.equals(DATA,Files.readAllBytes(f.toPath())))throw new AssertionError("byte mismatch");
    }
    private static void run(File f,int m,boolean restart)throws Exception{
        mode=m;requests=0;ranges.clear();
        ResumableModuleDownload.download(URL,"test",f,DATA.length,new AtomicBoolean(restart),x->{});
        verify(f);
    }
    public static void main(String[] args)throws Exception{
        java.net.URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            @Override protected URLConnection openConnection(java.net.URL u){return new Mock(u);}
        }:null);
        Path dir=Files.createTempDirectory("medialab-resume");
        File f=dir.resolve("pack.part").toFile();
        try{
            run(f,1,false);
            if(!ranges.equals(Arrays.asList("full","bytes=200000-")))throw new AssertionError(ranges);
            seed(f,123456);run(f,0,false);
            if(!ranges.equals(Collections.singletonList("bytes=123456-")))throw new AssertionError(ranges);
            seed(f,123456);run(f,2,false);
            if(!ranges.equals(Collections.singletonList("bytes=123456-")))throw new AssertionError(ranges);
            seed(f,123456);run(f,3,false);
            if(!ranges.equals(Arrays.asList("bytes=123456-","full")))throw new AssertionError(ranges);
            seed(f,123456);run(f,0,true);
            if(!ranges.equals(Collections.singletonList("full")))throw new AssertionError(ranges);
            seed(f,123456);Thread.currentThread().interrupt();
            try{
                ResumableModuleDownload.download(URL,"test",f,DATA.length,new AtomicBoolean(false),x->{});
                throw new AssertionError("pause ignored");
            }catch(InterruptedIOException expected){
                if(f.length()!=123456)throw new AssertionError("partial lost on pause");
            }finally{Thread.interrupted();}
            run(f,0,false);
            if(!ranges.equals(Collections.singletonList("bytes=123456-")))throw new AssertionError(ranges);
            System.out.println("PASS: truncated body, Range 206, Range ignored 200, stale 416, restart, pause and resume; exact bytes");
        }finally{Files.deleteIfExists(dir.resolve("pack.part.meta"));Files.deleteIfExists(f.toPath());Files.deleteIfExists(dir);}
    }
}
