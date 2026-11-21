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
    /** Inclusive final byte advertised by a validated 206 response. */
    static long end(String header){
        if(header==null)return -1;
        java.util.regex.Matcher m=java.util.regex.Pattern
            .compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(header);
        if(!m.matches())return -1;
        try{return Long.parseLong(m.group(2));}
        catch(NumberFormatException error){return -1;}
    }
    /** A declared body length must equal the advertised partial range span.
     * Unknown transfer length (-1) is allowed for chunked HTTPS responses. */
    static boolean lengthMatches(String header,long contentLength){
        if(contentLength<0)return true;
        if(header==null)return false;
        java.util.regex.Matcher m=java.util.regex.Pattern
            .compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(header);
        if(!m.matches())return false;
        try{
            long first=Long.parseLong(m.group(1));
            long last=Long.parseLong(m.group(2));
            return last>=first && last-first<Long.MAX_VALUE &&
                last-first+1==contentLength;
        }catch(NumberFormatException error){return false;}
    }
}
