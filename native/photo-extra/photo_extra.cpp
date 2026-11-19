#include <jni.h>
#include <android/bitmap.h>
#include <jxl/encode.h>
#include <jxl/decode.h>
#include <cstdio>
#include <cstdint>
#include <vector>
#include <memory>
#include <algorithm>
extern "C" int medialab_oxi(const char*,const char*,uint8_t);

namespace {
struct Utf {
    JNIEnv* e; jstring s; const char* p;
    Utf(JNIEnv* env,jstring value):e(env),s(value),p(value?env->GetStringUTFChars(value,nullptr):nullptr){}
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
bool encode(JNIEnv* env,jobject bitmap,const char* out,int q){
    Locked src(env,bitmap);
    if(!src.p||!src.info.width||!src.info.height||uint64_t(src.info.width)*src.info.height>24000000)return false;
    const size_t pixels=size_t(src.info.width)*src.info.height;
    std::vector<uint8_t> rgb(pixels*3);
    for(uint32_t y=0;y<src.info.height;y++){
        auto* row=static_cast<const uint8_t*>(src.p)+size_t(y)*src.info.stride;
        for(uint32_t x=0;x<src.info.width;x++){
            if(row[4*x+3]!=255)return false;
            std::copy_n(row+4*x,3,rgb.data()+3*(size_t(y)*src.info.width+x));
        }
    }
    std::unique_ptr<JxlEncoder,decltype(&JxlEncoderDestroy)> enc(JxlEncoderCreate(nullptr),JxlEncoderDestroy);
    if(!enc)return false;
    JxlBasicInfo info;JxlEncoderInitBasicInfo(&info);
    info.xsize=src.info.width;info.ysize=src.info.height;info.bits_per_sample=8;
    info.num_color_channels=3;info.uses_original_profile=q==100?JXL_TRUE:JXL_FALSE;
    if(JxlEncoderSetBasicInfo(enc.get(),&info)!=JXL_ENC_SUCCESS)return false;
    JxlColorEncoding color;JxlColorEncodingSetToSRGB(&color,JXL_FALSE);
    if(JxlEncoderSetColorEncoding(enc.get(),&color)!=JXL_ENC_SUCCESS)return false;
    auto* settings=JxlEncoderFrameSettingsCreate(enc.get(),nullptr);if(!settings)return false;
    if(JxlEncoderFrameSettingsSetOption(settings,JXL_ENC_FRAME_SETTING_EFFORT,7)!=JXL_ENC_SUCCESS)return false;
    if(q==100){if(JxlEncoderSetFrameLossless(settings,JXL_TRUE)!=JXL_ENC_SUCCESS)return false;}
    else if(JxlEncoderSetFrameDistance(settings,JxlEncoderDistanceFromQuality(q))!=JXL_ENC_SUCCESS)return false;
    JxlPixelFormat fmt{3,JXL_TYPE_UINT8,JXL_NATIVE_ENDIAN,0};
    if(JxlEncoderAddImageFrame(settings,&fmt,rgb.data(),rgb.size())!=JXL_ENC_SUCCESS)return false;
    JxlEncoderCloseInput(enc.get());
    std::unique_ptr<FILE,decltype(&fclose)> file(fopen(out,"wb"),fclose);if(!file)return false;
    uint8_t buf[65536];size_t total=0;
    for(;;){
        uint8_t* next=buf;size_t avail=sizeof(buf);
        auto st=JxlEncoderProcessOutput(enc.get(),&next,&avail);
        size_t n=sizeof(buf)-avail;
        if(n&&fwrite(buf,1,n,file.get())!=n)return false;
        total+=n;if(total>128*1024*1024)return false;
        if(st==JXL_ENC_SUCCESS)return total>0&&fflush(file.get())==0;
        if(st!=JXL_ENC_NEED_MORE_OUTPUT)return false;
    }
}
jobject decode(JNIEnv* env,const char* path){
    std::unique_ptr<FILE,decltype(&fclose)> f(fopen(path,"rb"),fclose);
    if(!f||fseek(f.get(),0,SEEK_END))return nullptr;
    long size=ftell(f.get());if(size<=0||size>128*1024*1024||fseek(f.get(),0,SEEK_SET))return nullptr;
    std::vector<uint8_t> input(size);if(fread(input.data(),1,input.size(),f.get())!=input.size())return nullptr;
    std::unique_ptr<JxlDecoder,decltype(&JxlDecoderDestroy)> dec(JxlDecoderCreate(nullptr),JxlDecoderDestroy);
    if(!dec||JxlDecoderSubscribeEvents(dec.get(),JXL_DEC_BASIC_INFO|JXL_DEC_COLOR_ENCODING|JXL_DEC_FULL_IMAGE)!=JXL_DEC_SUCCESS||
        JxlDecoderSetInput(dec.get(),input.data(),input.size())!=JXL_DEC_SUCCESS)return nullptr;
    JxlDecoderCloseInput(dec.get());
    JxlBasicInfo info{};JxlPixelFormat fmt{4,JXL_TYPE_UINT8,JXL_NATIVE_ENDIAN,0};std::vector<uint8_t> rgba;
    bool full=false;
    for(;;){
        JxlDecoderStatus st=JxlDecoderProcessInput(dec.get());
        if(st==JXL_DEC_BASIC_INFO){
            if(JxlDecoderGetBasicInfo(dec.get(),&info)!=JXL_DEC_SUCCESS||info.have_animation||
                info.bits_per_sample!=8||info.exponent_bits_per_sample||info.num_extra_channels||
                !info.xsize||!info.ysize||uint64_t(info.xsize)*info.ysize>24000000)return nullptr;
        }else if(st==JXL_DEC_COLOR_ENCODING){
            JxlColorEncoding profile{};
            if(JxlDecoderGetColorAsEncodedProfile(dec.get(),JXL_COLOR_PROFILE_TARGET_DATA,&profile)!=JXL_DEC_SUCCESS)return nullptr;
            // Avoid silently treating wide-gamut or HDR data as sRGB.
            if(profile.color_space!=JXL_COLOR_SPACE_RGB||profile.white_point!=JXL_WHITE_POINT_D65||
                profile.primaries!=JXL_PRIMARIES_SRGB||profile.transfer_function!=JXL_TRANSFER_FUNCTION_SRGB)return nullptr;
        }else if(st==JXL_DEC_NEED_IMAGE_OUT_BUFFER){
            size_t bytes=0;
            if(JxlDecoderImageOutBufferSize(dec.get(),&fmt,&bytes)!=JXL_DEC_SUCCESS||bytes!=uint64_t(info.xsize)*info.ysize*4)return nullptr;
            rgba.resize(bytes);
            if(JxlDecoderSetImageOutBuffer(dec.get(),&fmt,rgba.data(),rgba.size())!=JXL_DEC_SUCCESS)return nullptr;
        }else if(st==JXL_DEC_FULL_IMAGE){full=true;}
        else if(st==JXL_DEC_SUCCESS){break;}
        else return nullptr;
    }
    if(!full||rgba.empty())return nullptr;
    jclass bitmap=env->FindClass("android/graphics/Bitmap");
    jclass config=env->FindClass("android/graphics/Bitmap$Config");
    if(!bitmap||!config)return nullptr;
    jfieldID argb=env->GetStaticFieldID(config,"ARGB_8888","Landroid/graphics/Bitmap$Config;");
    jmethodID create=env->GetStaticMethodID(bitmap,"createBitmap","(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if(!argb||!create)return nullptr;
    jobject result=env->CallStaticObjectMethod(bitmap,create,jint(info.xsize),jint(info.ysize),env->GetStaticObjectField(config,argb));
    if(env->ExceptionCheck()||!result)return nullptr;
    {Locked dst(env,result);if(!dst.p)return nullptr;
        for(uint32_t y=0;y<info.ysize;y++)std::copy_n(rgba.data()+size_t(y)*info.xsize*4,size_t(info.xsize)*4,static_cast<uint8_t*>(dst.p)+size_t(y)*dst.info.stride);}
    jmethodID alpha=env->GetMethodID(bitmap,"setHasAlpha","(Z)V");
    if(alpha)env->CallVoidMethod(result,alpha,JNI_FALSE);
    return result;
}
}
extern "C" JNIEXPORT jint JNICALL Java_com_xarkon_medialab_PhotoExtraPack_nativeVersion(JNIEnv*,jclass){return 1;}
extern "C" JNIEXPORT jboolean JNICALL Java_com_xarkon_medialab_PhotoExtraPack_nativeOptimizePng(JNIEnv* e,jclass,jstring input,jstring output,jint preset){
    Utf a(e,input),b(e,output);if(!a.p||!b.p)return JNI_FALSE;
    return medialab_oxi(a.p,b.p,uint8_t(std::max(0,std::min(6,int(preset)))))?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_xarkon_medialab_PhotoExtraPack_nativeEncodeJxl(JNIEnv* e,jclass,jobject bitmap,jstring output,jint q){
    Utf path(e,output);if(!path.p||q<1||q>100)return JNI_FALSE;
    bool ok=false;try{ok=encode(e,bitmap,path.p,q);}catch(...){}
    if(!ok)remove(path.p);return ok?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jobject JNICALL Java_com_xarkon_medialab_PhotoExtraPack_nativeDecodeJxl(JNIEnv* e,jclass,jstring path){
    Utf p(e,path);if(!p.p)return nullptr;
    try{return decode(e,p.p);}catch(...){return nullptr;}
}
