package com.xarkon.medialab;

public final class TestOtaContentRange {
    private static void check(boolean expected,String header,long start,long size){
        if(OtaContentRange.matches(header,start,size)!=expected)
            throw new AssertionError("Range: "+header+" / "+start+" / "+size);
    }
    public static void main(String[] args){
        check(true,"bytes 100-999/1000",100,1000);
        check(true,"bytes 100-499/1000",100,1000);
        check(true,"bytes 0-499/1000",0,1000);
        check(false,"bytes 0-999/1000",100,1000);
        check(false,"bytes 100-1000/1000",100,1000);
        check(false,"bytes 100-99/1000",100,1000);
        check(false,"bytes 100-999/1001",100,1000);
        check(false,"bytes */1000",100,1000);
        check(false,"bytes 100-999/*",100,1000);
        check(false,"bytes 100-999/1000",1000,1000);
        check(false,"bytes 100-999/1000",100,-1);
        check(false,"bytes 100-999/1000\\r\\nInjected: yes",100,1000);
        check(false,"bytes 9223372036854775808-999/1000",100,1000);
        check(false,null,100,1000);
        System.out.println("OTA Content-Range validation passed");
    }
}
