#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <errno.h>
#include <string.h>
#include <sys/stat.h>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/opt.h>
#include <libavutil/pixfmt.h>
}
namespace {
struct Utf {
    JNIEnv* e; jstring s; const char* p;
    Utf(JNIEnv* env,jstring value):e(env),s(value),p(value?env->GetStringUTFChars(value,nullptr):nullptr){}
    ~Utf(){if(p)e->ReleaseStringUTFChars(s,p);}
};
struct Callback {
    JNIEnv* env; jobject target; jmethodID method; bool stopped=false;
    int64_t frames=0,ms=0;
    bool update(){
        if(stopped)return false;
        stopped=env->CallBooleanMethod(target,method,jlong(ms),jlong(frames))!=JNI_TRUE||env->ExceptionCheck();
        return !stopped;
    }
};
int interrupted(void* p){return static_cast<Callback*>(p)->stopped?1:0;}
struct Job {
    AVFormatContext* input=nullptr;
    AVFormatContext* output=nullptr;
    AVCodecContext* decoder=nullptr;
    AVCodecContext* encoder=nullptr;
    AVPacket* packet=nullptr;
    AVPacket* encoded=nullptr;
    AVFrame* frame=nullptr;
    int video=-1;
    int64_t cap=0,lastPts=AV_NOPTS_VALUE,firstPts=AV_NOPTS_VALUE;
    Callback* callback;
    explicit Job(Callback* c):callback(c){}
    ~Job(){
        av_packet_free(&packet);av_packet_free(&encoded);av_frame_free(&frame);
        avcodec_free_context(&decoder);avcodec_free_context(&encoder);
        avformat_close_input(&input);
        if(output){if(output->pb)avio_closep(&output->pb);avformat_free_context(output);}
    }
    bool space(){
        return output&&output->pb&&(cap<=0||avio_tell(output->pb)<=cap);
    }
    int drainEncoder(){
        for(;;){
            int r=avcodec_receive_packet(encoder,encoded);
            if(r==AVERROR(EAGAIN)||r==AVERROR_EOF)return 0;
            if(r<0)return r;
            av_packet_rescale_ts(encoded,encoder->time_base,output->streams[video]->time_base);
            encoded->stream_index=video;encoded->pos=-1;
            r=av_interleaved_write_frame(output,encoded);av_packet_unref(encoded);
            if(r<0)return r;
            if(!space())return AVERROR(ENOSPC);
            if(!callback->update())return AVERROR_EXIT;
        }
    }
    int drainDecoder(){
        for(;;){
            int r=avcodec_receive_frame(decoder,frame);
            if(r==AVERROR(EAGAIN)||r==AVERROR_EOF)return 0;
            if(r<0)return r;
            // Never silently downconvert chroma, depth, HDR, rotated or damaged video.
            if(frame->format!=AV_PIX_FMT_YUV420P||frame->width!=encoder->width||frame->height!=encoder->height||
                frame->color_trc==AVCOL_TRC_SMPTE2084||frame->color_trc==AVCOL_TRC_ARIB_STD_B67||
                frame->flags&AV_FRAME_FLAG_CORRUPT)return AVERROR(EINVAL);
            int64_t pts=frame->best_effort_timestamp;
            if(pts==AV_NOPTS_VALUE||(lastPts!=AV_NOPTS_VALUE&&pts<=lastPts))return AVERROR(EINVAL);
            if(firstPts==AV_NOPTS_VALUE)firstPts=pts;
            lastPts=pts;frame->pts=pts;frame->pict_type=AV_PICTURE_TYPE_NONE;
            callback->ms=av_rescale_q(pts-firstPts,encoder->time_base,AVRational{1,1000});
            callback->frames++;
            if(!callback->update())return AVERROR_EXIT;
            r=avcodec_send_frame(encoder,frame);av_frame_unref(frame);
            if(r<0)return r;
            if((r=drainEncoder())<0)return r;
        }
    }
    int run(const char* in,const char* out,int quality,int preset,int threads,int64_t limit){
        if(!in||!out||strcmp(in,out)==0||quality<1||quality>63||preset<0||preset>13)return AVERROR(EINVAL);
        cap=limit;input=avformat_alloc_context();if(!input)return AVERROR(ENOMEM);
        input->interrupt_callback={interrupted,callback};
        int r=avformat_open_input(&input,in,nullptr,nullptr);if(r<0)return r;
        if((r=avformat_find_stream_info(input,nullptr))<0)return r;
        // Keep existing engine as fallback for unusual stream layouts, chapters and rotations.
        if(input->nb_chapters||input->nb_programs||input->nb_streams>64)return AVERROR(EINVAL);
        for(unsigned i=0;i<input->nb_streams;i++){
            AVCodecParameters* p=input->streams[i]->codecpar;
            if(p->codec_type==AVMEDIA_TYPE_VIDEO){
                if(video!=-1||input->streams[i]->disposition&AV_DISPOSITION_ATTACHED_PIC)return AVERROR(EINVAL);
                video=int(i);
            }
        }
        if(video<0)return AVERROR(EINVAL);
        AVStream* src=input->streams[video];AVCodecParameters* p=src->codecpar;
        if(p->width<64||p->height<64||p->width>4096||p->height>4096||(p->width&1)||(p->height&1)||
            p->color_trc==AVCOL_TRC_SMPTE2084||p->color_trc==AVCOL_TRC_ARIB_STD_B67||
            p->nb_coded_side_data||src->time_base.num<=0||src->time_base.den<=0)return AVERROR(EINVAL);
        const AVCodec* dec=avcodec_find_decoder(p->codec_id);
        const AVCodec* enc=avcodec_find_encoder_by_name("libsvtav1");
        if(!dec||!enc)return AVERROR_ENCODER_NOT_FOUND;
        decoder=avcodec_alloc_context3(dec);encoder=avcodec_alloc_context3(enc);
        if(!decoder||!encoder)return AVERROR(ENOMEM);
        if((r=avcodec_parameters_to_context(decoder,p))<0)return r;
        decoder->pkt_timebase=src->time_base;decoder->thread_count=threads;
        if((r=avcodec_open2(decoder,dec,nullptr))<0)return r;
        if((r=avformat_alloc_output_context2(&output,nullptr,"matroska",out))<0||!output)return r<0?r:AVERROR(ENOMEM);
        encoder->width=p->width;encoder->height=p->height;encoder->pix_fmt=AV_PIX_FMT_YUV420P;
        encoder->time_base=src->time_base;encoder->framerate=av_guess_frame_rate(input,src,nullptr);
        if(encoder->framerate.num<=0||encoder->framerate.den<=0||av_q2d(encoder->framerate)>120)return AVERROR(EINVAL);
        encoder->sample_aspect_ratio=p->sample_aspect_ratio;
        encoder->color_range=p->color_range;encoder->color_primaries=p->color_primaries;
        encoder->color_trc=p->color_trc;encoder->colorspace=p->color_space;encoder->chroma_sample_location=p->chroma_location;
        encoder->thread_count=threads;
        if(output->oformat->flags&AVFMT_GLOBALHEADER)encoder->flags|=AV_CODEC_FLAG_GLOBAL_HEADER;
        if((r=av_opt_set_int(encoder->priv_data,"crf",quality,0))<0)return r;
        if((r=av_opt_set_int(encoder->priv_data,"preset",preset,0))<0)return r;
        if((r=avcodec_open2(encoder,enc,nullptr))<0)return r;
        for(unsigned i=0;i<input->nb_streams;i++){
            AVStream* a=input->streams[i];AVStream* b=avformat_new_stream(output,nullptr);
            if(!b)return AVERROR(ENOMEM);
            r=int(i)==video?avcodec_parameters_from_context(b->codecpar,encoder):avcodec_parameters_copy(b->codecpar,a->codecpar);
            if(r<0)return r;
            b->codecpar->codec_tag=0;b->time_base=int(i)==video?encoder->time_base:a->time_base;
            b->avg_frame_rate=a->avg_frame_rate;b->disposition=a->disposition;
            if((r=av_dict_copy(&b->metadata,a->metadata,0))<0)return r;
        }
        if((r=av_dict_copy(&output->metadata,input->metadata,0))<0)return r;
        if((r=avio_open2(&output->pb,out,AVIO_FLAG_WRITE,&input->interrupt_callback,nullptr))<0)return r;
        if((r=avformat_write_header(output,nullptr))<0)return r;
        packet=av_packet_alloc();encoded=av_packet_alloc();frame=av_frame_alloc();
        if(!packet||!encoded||!frame)return AVERROR(ENOMEM);
        while((r=av_read_frame(input,packet))>=0){
            if(!callback->update())return AVERROR_EXIT;
            int idx=packet->stream_index;
            if(idx<0||unsigned(idx)>=input->nb_streams)return AVERROR(EINVAL);
            if(idx==video){
                r=avcodec_send_packet(decoder,packet);av_packet_unref(packet);
                if(r<0)return r;if((r=drainDecoder())<0)return r;
            }else{
                av_packet_rescale_ts(packet,input->streams[idx]->time_base,output->streams[idx]->time_base);
                packet->pos=-1;r=av_interleaved_write_frame(output,packet);av_packet_unref(packet);
                if(r<0)return r;if(!space())return AVERROR(ENOSPC);
            }
        }
        if(r!=AVERROR_EOF||!callback->update())return r==AVERROR_EOF?AVERROR_EXIT:r;
        if((r=avcodec_send_packet(decoder,nullptr))<0)return r;
        if((r=drainDecoder())<0)return r;
        if((r=avcodec_send_frame(encoder,nullptr))<0)return r;
        if((r=drainEncoder())<0)return r;
        if(callback->frames<=0)return AVERROR(EINVAL);
        if((r=av_write_trailer(output))<0)return r;
        if(!space()||!callback->update())return AVERROR_EXIT;
        return avio_closep(&output->pb);
    }
};
}
extern "C" JNIEXPORT jint JNICALL Java_com_xarkon_medialab_VideoUltraPack_nativeVersion(JNIEnv*,jclass){return 1;}
extern "C" JNIEXPORT jint JNICALL Java_com_xarkon_medialab_VideoUltraPack_nativeEncode(JNIEnv* env,jclass,jstring input,jstring output,jint quality,jint preset,jint threads,jlong cap,jobject progress){
    Utf in(env,input),out(env,output);
    if(!in.p||!out.p||!progress)return AVERROR(EINVAL);
    jclass cls=env->GetObjectClass(progress);
    jmethodID method=cls?env->GetMethodID(cls,"update","(JJ)Z"):nullptr;
    if(!method)return AVERROR(EINVAL);
    Callback cb{env,progress,method};
    int rc;
    {Job job(&cb);rc=job.run(in.p,out.p,quality,preset,threads,cap);}
    if(rc<0)remove(out.p);
    return rc;
}
