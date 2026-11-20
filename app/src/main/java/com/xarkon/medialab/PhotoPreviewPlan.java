package com.xarkon.medialab;

/** Independent, testable bounds for visual previews; never used for final exports. */
final class PhotoPreviewPlan {
    static final int MAX_EDGE=1600;
    static final long MAX_PIXELS=2_000_000L;
    private PhotoPreviewPlan(){}

    static boolean enabled(boolean ultra,boolean preview,String format){
        return ultra&&preview&&("jpg".equals(format)||"webp".equals(format)||
                "avif".equals(format)||"png".equals(format));
    }

    static int quality(String format){
        if("png".equals(format))return 100;
        if("avif".equals(format))return 84;
        return 90;
    }

    static int[] target(int width,int height){
        if(width<=0||height<=0)throw new IllegalArgumentException("Invalid image dimensions");
        double scale=Math.min(1.0,Math.min(MAX_EDGE/(double)Math.max(width,height),
                Math.sqrt(MAX_PIXELS/(double)((long)width*height))));
        int w=Math.max(1,(int)Math.floor(width*scale));
        int h=Math.max(1,(int)Math.floor(height*scale));
        return new int[]{w,h};
    }
}
