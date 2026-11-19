#include <jni.h>
#include <android/bitmap.h>
#include <avif/avif.h>
#include <algorithm>
#include <cstdio>
#include <cstdint>
#include <memory>

namespace {
struct Utf {
    JNIEnv* e; jstring s; const char* p;
    Utf(JNIEnv* env,jstring v):e(env),s(v),p(v?env->GetStringUTFChars(v,nullptr):nullptr){}
    ~Utf(){if(p)e->ReleaseStringUTFChars(s,p);}
};
struct Locked {
    JNIEnv* e;jobject b;void* p=nullptr;AndroidBitmapInfo info{};
    Locked(JNIEnv* env,jobject bitmap):e(env),b(bitmap){
        if(b&&AndroidBitmap_getInfo(e,b,&info)==ANDROID_BITMAP_RESULT_SUCCESS&&info.format==ANDROID_BITMAP_FORMAT_RGBA_8888)
            if(AndroidBitmap_lockPixels(e,b,&p)!=ANDROID_BITMAP_RESULT_SUCCESS)p=nullptr;
    }
    ~Locked(){if(p)AndroidBitmap_unlockPixels(e,b);}
};
bool encode(JNIEnv* e,jobject bitmap,const char* path,int q,int speed,int threads){
    Locked src(e,bitmap);
    if(!src.p||!src.info.width||!src.info.height||uint64_t(src.info.width)*src.info.height>24000000||
       src.info.stride<uint64_t(src.info.width)*4)return false;
    // 4:4:4 retains fine chroma detail. At 100 use reversible RGB identity transform.
    std::unique_ptr<avifImage,decltype(&avifImageDestroy)> img(
        avifImageCreate(src.info.width,src.info.height,8,AVIF_PIXEL_FORMAT_YUV444),avifImageDestroy);
    if(!img)return false;
    img->yuvRange=AVIF_RANGE_FULL;
    img->colorPrimaries=AVIF_COLOR_PRIMARIES_BT709;
    img->transferCharacteristics=AVIF_TRANSFER_CHARACTERISTICS_SRGB;
    img->matrixCoefficients=q==100?AVIF_MATRIX_COEFFICIENTS_IDENTITY:AVIF_MATRIX_COEFFICIENTS_BT709;
    avifRGBImage rgb;avifRGBImageSetDefaults(&rgb,img.get());
    rgb.format=AVIF_RGB_FORMAT_RGBA;rgb.pixels=static_cast<uint8_t*>(src.p);rgb.rowBytes=src.info.stride;
    rgb.ignoreAlpha=AVIF_TRUE;
    // Do not flatten transparency.
    for(uint32_t y=0;y<src.info.height;y++){
        const auto* row=rgb.pixels+size_t(y)*rgb.rowBytes;
        for(uint32_t x=0;x<src.info.width;x++)if(row[x*4+3]!=255)return false;
    }
    if(avifImageRGBToYUV(img.get(),&rgb)!=AVIF_RESULT_OK)return false;
    std::unique_ptr<avifEncoder,decltype(&avifEncoderDestroy)> enc(avifEncoderCreate(),avifEncoderDestroy);
    if(!enc)return false;
    enc->codecChoice=AVIF_CODEC_CHOICE_AOM;enc->quality=q;enc->qualityAlpha=100;
    enc->speed=std::clamp(speed,0,10);enc->maxThreads=std::clamp(threads,1,8);
    avifRWData out=AVIF_DATA_EMPTY;
    avifResult result=avifEncoderWrite(enc.get(),img.get(),&out);
    bool ok=false;
    if(result==AVIF_RESULT_OK&&out.size>0&&out.size<=128*1024*1024){
        FILE* f=fopen(path,"wb");
        if(f){ok=fwrite(out.data,1,out.size,f)==out.size;if(fclose(f)!=0)ok=false;}
    }
    avifRWDataFree(&out);return ok;
}
}
extern "C" JNIEXPORT jint JNICALL Java_com_xarkon_medialab_AvifUltraPack_nativeVersion(JNIEnv*,jclass){return 1;}
extern "C" JNIEXPORT jboolean JNICALL Java_com_xarkon_medialab_AvifUltraPack_nativeEncode(JNIEnv* e,jclass,jobject bitmap,jstring path,jint q,jint speed,jint threads){
    Utf name(e,path);if(!name.p||q<1||q>100)return JNI_FALSE;
    bool ok=false;try{ok=encode(e,bitmap,name.p,q,speed,threads);}catch(...){}
    if(!ok)remove(name.p);return ok?JNI_TRUE:JNI_FALSE;
}
