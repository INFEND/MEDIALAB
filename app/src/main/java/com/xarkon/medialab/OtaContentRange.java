package com.xarkon.medialab;

/** Strict RFC 9110 Content-Range validator for resumable OTA downloads. */
final class OtaContentRange {
    private OtaContentRange(){}
    static boolean matches(String header,long requestedStart,long expectedTotal){
        if(header==null||requestedStart<0||expectedTotal<=0||requestedStart>=expectedTotal)return false;
        java.util.regex.Matcher m=java.util.regex.Pattern
            .compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(header);
        if(!m.matches())return false;
        try{
            long first=Long.parseLong(m.group(1));
            long last=Long.parseLong(m.group(2));
            long total=Long.parseLong(m.group(3));
            return first==requestedStart&&last>=first&&last<total&&total==expectedTotal;
        }catch(NumberFormatException e){return false;}
    }
}
