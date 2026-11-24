package com.chaoseye.lyrilayer
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AppRelease(val tag:String,val url:String,val size:Long,val digest:String,val notes:String)
internal data class UpdateState(val stage:String="idle",val release:AppRelease?=null,val percent:Int=0,val message:String="")
internal object AppUpdates {
    const val AUTO_CHECK="update_auto_check"
    const val AUTO_DOWNLOAD="update_auto_download"
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(40,TimeUnit.SECONDS).callTimeout(5,TimeUnit.MINUTES).build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val gate=Mutex()
    private val _state=MutableStateFlow(UpdateState())
    val state=_state.asStateFlow()
    private var lastAutoCheck=0L
    fun supported(c:Context)=c.packageName==UpdateRules.PACKAGE
    private fun flags()=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    private fun info(c:Context)=c.packageManager.getPackageInfo(c.packageName,flags())
    fun current(c:Context)=info(c).versionName.orEmpty()
    @Suppress("DEPRECATION")
    private fun code(info:PackageInfo)=if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
    private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
    @Suppress("DEPRECATION")
    private fun signed(info:PackageInfo):Boolean{
        val certs=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures
        return certs?.size==1&&hex(MessageDigest.getInstance("SHA-256").digest(certs[0].toByteArray()))==UpdateRules.CERT
    }
    private fun dir(c:Context)=File(c.cacheDir,"updates").apply{mkdirs()}
    private fun apk(c:Context,r:AppRelease)=File(dir(c),r.tag+".apk")
    private fun request(url:String)=Request.Builder().url(url).header("User-Agent","LyriLayer-Android-Updater").build()
    private fun newest(c:Context):AppRelease?{
        val installed=current(c)
        var latest:AppRelease?=null
        client.newCall(request("https://api.github.com/repos/contigo/LyriLayer/releases?per_page=30")).execute().use{response->
            if(!response.isSuccessful)throw IOException("업데이트 서버 HTTP "+response.code)
            val all=JSONArray(response.body?.string()?:throw IOException("응답 없음"))
            for(i in 0 until all.length()){
                val r=all.getJSONObject(i);val tag=r.optString("tag_name")
                if(r.optBoolean("draft")||!r.optBoolean("prerelease")||UpdateRules.compare(tag,installed)<=0)continue
                if(latest!=null&&UpdateRules.compare(tag,latest!!.tag)<=0)continue
                val assets=r.optJSONArray("assets")?:continue
                for(j in 0 until assets.length()){
                    val a=assets.getJSONObject(j)
                    if(a.optString("name")!=UpdateRules.ASSET||a.optString("state")!="uploaded")continue
                    val url=a.optString("browser_download_url");val size=a.optLong("size");val digest=a.optString("digest")
                    if(!UpdateRules.valid(tag,url,size,digest))continue
                    latest=AppRelease(tag,url,size,digest,r.optString("body").take(4000));break
                }
            }
        }
        return latest
    }
    @Suppress("DEPRECATION")
    private fun verify(c:Context,r:AppRelease,file:File){
        if(!supported(c)||!UpdateRules.valid(r.tag,r.url,r.size,r.digest)||file.length()!=r.size)throw IOException("APK 크기 또는 배포 정보 오류")
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val buf=ByteArray(65536);while(true){val n=input.read(buf);if(n<0)break;hash.update(buf,0,n)}}
        if("sha256:"+hex(hash.digest())!=r.digest.lowercase())throw IOException("APK SHA-256 검증 실패")
        val installed=info(c)
        val archive=c.packageManager.getPackageArchiveInfo(file.absolutePath,flags())?:throw IOException("APK 정보 확인 실패")
        if(archive.packageName!=c.packageName||archive.versionName!=r.tag.removePrefix("v")||code(archive)<=code(installed))throw IOException("패키지 또는 버전 불일치")
        if(!signed(installed)||!signed(archive))throw IOException("서명 불일치 · 설치된 앱과 업데이트 서명을 확인하세요")
    }
    fun check(context:Context,automatic:Boolean=false){
        val c=context.applicationContext
        if(!supported(c))return
        if(automatic){
            if(!c.getSharedPreferences("settings",Context.MODE_PRIVATE).getBoolean(AUTO_CHECK,true))return
            val now=android.os.SystemClock.elapsedRealtime()
            if(lastAutoCheck!=0L&&now-lastAutoCheck<30*60*1000)return
            lastAutoCheck=now
        }
        scope.launch{
            if(!gate.tryLock())return@launch
            try{
                _state.value=UpdateState("checking",message="최신 버전 확인 중")
                val r=withContext(Dispatchers.IO){newest(c)}
                if(r==null){_state.value=UpdateState("current",message="최신 버전입니다");return@launch}
                if(withContext(Dispatchers.IO){runCatching{verify(c,r,apk(c,r))}.isSuccess}){
                    _state.value=UpdateState("ready",r,100,"다운로드 및 검증 완료");return@launch
                }
                _state.value=UpdateState("available",r,message="새 버전 "+r.tag)
                val auto=c.getSharedPreferences("settings",Context.MODE_PRIVATE).getBoolean(AUTO_DOWNLOAD,true)
                val wifi=!(c.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered?:true)
                if(automatic&&auto&&wifi)downloadLocked(c,r)
            }catch(e:CancellationException){throw e}catch(e:Exception){_state.value=UpdateState("error",_state.value.release,message=e.message?:"업데이트 확인 실패")}
            finally{gate.unlock()}
        }
    }
    fun download(context:Context){
        val c=context.applicationContext;val r=_state.value.release?:return
        if(!supported(c))return
        scope.launch{
            if(!gate.tryLock())return@launch
            try{downloadLocked(c,r)}
            catch(e:CancellationException){throw e}
            catch(e:Exception){_state.value=UpdateState("error",r,message=e.message?:"다운로드 실패")}
            finally{gate.unlock()}
        }
    }
    private suspend fun downloadLocked(c:Context,r:AppRelease){
        _state.value=UpdateState("downloading",r,0,"APK 다운로드 중")
        withContext(Dispatchers.IO){
            val dst=apk(c,r);val tmp=File(dir(c),r.tag+".part.apk")
            try{
                client.newCall(request(r.url)).execute().use{response->
                    if(!response.isSuccessful||!response.request.url.isHttps)throw IOException("APK 다운로드 실패 HTTP "+response.code)
                    val body=response.body?:throw IOException("APK 응답 없음")
                    var total=0L
                    body.byteStream().use{input->tmp.outputStream().use{output->
                        val buf=ByteArray(65536)
                        while(true){
                            currentCoroutineContext().ensureActive()
                            val n=input.read(buf);if(n<0)break
                            total+=n;if(total>r.size)throw IOException("APK 용량 초과")
                            output.write(buf,0,n)
                            _state.value=UpdateState("downloading",r,(total*100/r.size).toInt(),"APK 다운로드 중")
                        }
                    }}
                }
                verify(c,r,tmp)
                if(!tmp.renameTo(dst))throw IOException("APK 저장 실패")
                dir(c).listFiles()?.filter{it!=dst}?.forEach{it.delete()}
            }finally{tmp.delete()}
        }
        _state.value=UpdateState("ready",r,100,"다운로드 및 검증 완료")
    }
    fun install(context:Context){
        val r=_state.value.release?:return
        if(!supported(context)||_state.value.stage!="ready")return
        scope.launch{
            if(!gate.tryLock())return@launch
            try{
                val file=apk(context,r)
                withContext(Dispatchers.IO){verify(context,r,file)}
                if(!context.packageManager.canRequestPackageInstalls()){
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.packageName)))
                    _state.value=UpdateState("ready",r,100,"이 출처 허용 후 설치 버튼을 다시 누르세요.")
                }else{
                    val uri=FileProvider.getUriForFile(context,context.packageName+".updates",file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }catch(e:CancellationException){throw e}catch(e:Exception){_state.value=UpdateState("error",r,message=e.message?:"설치 실행 실패")}
            finally{gate.unlock()}
        }
    }
}
