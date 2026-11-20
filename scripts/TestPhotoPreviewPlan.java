package com.xarkon.medialab;

/** Lightweight JVM regression tests of fast preview bounds and export isolation. */
public final class TestPhotoPreviewPlan {
    private static void assertTarget(int width,int height){
        int[] dimensions=PhotoPreviewPlan.target(width,height);
        if(dimensions[0]<1||dimensions[1]<1||
            dimensions[0]>PhotoPreviewPlan.MAX_EDGE||dimensions[1]>PhotoPreviewPlan.MAX_EDGE||
            (long)dimensions[0]*dimensions[1]>PhotoPreviewPlan.MAX_PIXELS)
            throw new AssertionError("Unsafe preview size "+width+"x"+height);
    }
    public static void main(String[] args){
        int[] small=PhotoPreviewPlan.target(800,600);
        if(small[0]!=800||small[1]!=600)throw new AssertionError("Small image was resized");
        for(int[] dimensions:new int[][]{{4032,3024},{8000,6000},{1600,1600},{720,1280},{12000,600},{1,100000},{100000,1},{1,1}})
            assertTarget(dimensions[0],dimensions[1]);
        if(!PhotoPreviewPlan.enabled(true,true,"webp")||!PhotoPreviewPlan.enabled(true,true,"avif")||
            PhotoPreviewPlan.enabled(true,false,"webp")||PhotoPreviewPlan.enabled(false,true,"webp")||
            PhotoPreviewPlan.enabled(true,true,"jxl"))
            throw new AssertionError("Fast preview may affect final output or unsupported codecs");
        for(String format:new String[]{"jpg","webp","avif","png"}){
            int q=PhotoPreviewPlan.quality(format);
            if(q<1||q>100)throw new AssertionError(format+" quality");
        }
        System.out.println("Fast ASC ULTRA photo preview bounds and export isolation verified");
    }
}
