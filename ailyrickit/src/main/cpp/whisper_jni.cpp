#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

static std::vector<float> samples(JNIEnv *e,jfloatArray pcm) {
    if (!pcm) return {};
    const auto n=e->GetArrayLength(pcm);
    if(n<=0 || n>480000) return {};
    std::vector<float> out(n); e->GetFloatArrayRegion(pcm,0,n,out.data());
    if(e->ExceptionCheck())return {}; return out;
}
static jbyteArray bytes(JNIEnv *e,const std::string &s) {
    auto a=e->NewByteArray(static_cast<jsize>(s.size()));
    if(a&&!s.empty())e->SetByteArrayRegion(a,0,static_cast<jsize>(s.size()),reinterpret_cast<const jbyte*>(s.data()));
    return a;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_chaoseye_ailyrickit_engine_NativeWhisperBackend_nativeOpen(JNIEnv *e,jobject,jstring path) {
    if(!path)return 0; const char *p=e->GetStringUTFChars(path,nullptr);if(!p)return 0;
    auto params=whisper_context_default_params();params.use_gpu=false;
    auto ctx=whisper_init_from_file_with_params(p,params);e->ReleaseStringUTFChars(path,p);return reinterpret_cast<jlong>(ctx);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_chaoseye_ailyrickit_engine_NativeWhisperBackend_nativeDetect(JNIEnv *e,jobject,jlong handle,jfloatArray pcm,jint threads) {
    auto ctx=reinterpret_cast<whisper_context*>(handle);auto x=samples(e,pcm);if(!ctx||x.empty())return nullptr;
    int t=std::max(1,std::min(8,(int)threads));
    if(whisper_pcm_to_mel(ctx,x.data(),static_cast<int>(x.size()),t)!=0)return nullptr;
    std::vector<float> prob(whisper_lang_max_id()+1,0.f);
    int id=whisper_lang_auto_detect(ctx,0,t,prob.data()); if(id<0||id>whisper_lang_max_id())return nullptr;
    auto lang=whisper_lang_str(id); if(!lang)return nullptr;
    return bytes(e,std::string(lang)+"\t"+std::to_string(prob[id]));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_chaoseye_ailyrickit_engine_NativeWhisperBackend_nativeDecode(JNIEnv *e,jobject,jlong handle,jfloatArray pcm,jint threads,jstring language) {
    auto ctx=reinterpret_cast<whisper_context*>(handle);auto x=samples(e,pcm);if(!ctx||x.empty()||!language)return nullptr;
    const char *lang=e->GetStringUTFChars(language,nullptr);if(!lang)return nullptr;
    auto p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.language=lang;p.n_threads=std::max(1,std::min(8,(int)threads));p.translate=false;p.no_context=true;
    p.print_progress=false;p.print_realtime=false;p.print_timestamps=false;p.print_special=false;p.suppress_blank=true;
    p.token_timestamps=true;p.max_len=56;p.split_on_word=true;p.n_max_text_ctx=0;p.no_speech_thold=.65f;p.entropy_thold=2.4f;p.logprob_thold=-1.f;
    p.temperature_inc=0.0f;
    int rc=whisper_full(ctx,p,x.data(),static_cast<int>(x.size()));e->ReleaseStringUTFChars(language,lang);if(rc!=0)return nullptr;
    std::string out;
    for(int i=0;i<whisper_full_n_segments(ctx);i++){
        const char *raw=whisper_full_get_segment_text(ctx,i);if(!raw)continue;
        std::string text(raw);for(char &c:text)if(c=='\t'||c=='\n'||c=='\r')c=' ';
        long long a=10LL*whisper_full_get_segment_t0(ctx,i), b=10LL*whisper_full_get_segment_t1(ctx,i);if(a<0||b<=a)continue;
        double sum=0;int n=0;for(int j=0;j<whisper_full_n_tokens(ctx,i);j++){
            if(whisper_full_get_token_id(ctx,i,j)>=whisper_token_eot(ctx))continue;
            float q=whisper_full_get_token_p(ctx,i,j);if(std::isfinite(q)&&q>=0&&q<=1){sum+=q;n++;}
        }
        out+=std::to_string(a)+"\t"+std::to_string(b)+"\t"+(n?std::to_string(sum/n):"")+"\t"+text+"\n";
    }
    return bytes(e,out);
}
extern "C" JNIEXPORT void JNICALL Java_com_chaoseye_ailyrickit_engine_NativeWhisperBackend_nativeClose(JNIEnv *,jobject,jlong h){if(h)whisper_free(reinterpret_cast<whisper_context*>(h));}
