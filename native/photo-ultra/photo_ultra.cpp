#include <jni.h>
#include <android/bitmap.h>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <csetjmp>
#include <cstring>
#include "lib/jpegli/encode.h"
#include "webp/encode.h"

struct JpegError { jpeg_error_mgr base; jmp_buf jump; };
static void onJpegError(j_common_ptr c) {
    auto* err=reinterpret_cast<JpegError*>(c->err);
    longjmp(err->jump,1);
}
static bool jpeg(const AndroidBitmapInfo& info, const uint8_t* data, FILE* file, int quality) {
    auto* c=static_cast<jpeg_compress_struct*>(calloc(1,sizeof(jpeg_compress_struct)));
    auto* row=static_cast<uint8_t*>(malloc(size_t(info.width)*3));
    if(!c||!row){free(c);free(row);return false;}
    JpegError e{};
    c->err=jpegli_std_error(&e.base);
    e.base.error_exit=onJpegError;
    if(setjmp(e.jump)){jpegli_destroy_compress(c);free(row);free(c);return false;}
    jpegli_create_compress(c);
    jpegli_stdio_dest(c,file);
    c->image_width=info.width;c->image_height=info.height;
    c->input_components=3;c->in_color_space=JCS_RGB;
    jpegli_set_defaults(c);
    jpegli_set_quality(c,quality,TRUE);
    jpegli_start_compress(c,TRUE);
    while(c->next_scanline<c->image_height){
        const uint8_t* src=data+size_t(c->next_scanline)*info.stride;
        for(uint32_t x=0;x<info.width;x++){
            row[x*3]=src[x*4];row[x*3+1]=src[x*4+1];row[x*3+2]=src[x*4+2];
        }
        JSAMPROW rows[1]={row};
        if(jpegli_write_scanlines(c,rows,1)!=1){jpegli_destroy_compress(c);free(row);free(c);return false;}
    }
    jpegli_finish_compress(c);jpegli_destroy_compress(c);free(row);free(c);return true;
}
static int writeWebp(const uint8_t* data,size_t size,const WebPPicture* picture){
    auto* f=static_cast<FILE*>(picture->custom_ptr);
    return fwrite(data,1,size,f)==size;
}
static bool webp(const AndroidBitmapInfo& info,const uint8_t* data,FILE* file,int quality){
    WebPConfig config;
    if(!WebPConfigInit(&config))return false;
    config.quality=quality;config.method=6;config.thread_level=1;
    config.lossless=quality==100;config.use_sharp_yuv=1;
    if(!WebPValidateConfig(&config))return false;
    WebPPicture picture;
    if(!WebPPictureInit(&picture))return false;
    picture.width=info.width;picture.height=info.height;picture.use_argb=1;
    picture.writer=writeWebp;picture.custom_ptr=file;
    bool ok=WebPPictureImportRGBA(&picture,data,info.stride)&&WebPEncode(&config,&picture);
    WebPPictureFree(&picture);return ok;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_xarkon_medialab_PhotoUltraPack_nativeVersion(JNIEnv*,jclass){return 1;}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xarkon_medialab_PhotoUltraPack_nativeEncode(JNIEnv* env,jclass,jobject bitmap,jstring path,jint type,jint quality){
    if(!bitmap||!path||quality<1||quality>100||(type!=0&&type!=1))return JNI_FALSE;
    AndroidBitmapInfo info{};
    if(AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS||
       info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888||!info.width||!info.height||
       uint64_t(info.width)*info.height>24000000||info.stride<uint64_t(info.width)*4)return JNI_FALSE;
    void* pixels=nullptr;
    if(AndroidBitmap_lockPixels(env,bitmap,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS)return JNI_FALSE;
    const char* name=env->GetStringUTFChars(path,nullptr);
    bool ok=false;
    if(name){
        FILE* file=fopen(name,"wb");
        if(file){ok=type==0?jpeg(info,static_cast<const uint8_t*>(pixels),file,quality):webp(info,static_cast<const uint8_t*>(pixels),file,quality);
            if(fclose(file)!=0)ok=false;
            if(!ok)remove(name);
        }
        env->ReleaseStringUTFChars(path,name);
    }
    AndroidBitmap_unlockPixels(env,bitmap);
    return ok?JNI_TRUE:JNI_FALSE;
}
