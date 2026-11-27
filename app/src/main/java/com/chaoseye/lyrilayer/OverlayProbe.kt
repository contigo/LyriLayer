package com.chaoseye.lyrilayer

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** One-shot diagnostic independent of Samsung Music and the listener service. */
internal object OverlayProbe {
    suspend fun run(context:Context,status:(String)->Unit){
        if(!Settings.canDrawOverlays(context)){status("다른 앱 위에 표시 권한 필요");return}
        val app=context.applicationContext
        var wm:WindowManager?=null
        var view:TextView?=null
        try{
            val ctx=if(Build.VERSION.SDK_INT>=30){
                val display=app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                    ?:error("디스플레이를 찾을 수 없습니다")
                app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
            }else app
            val manager=ctx.getSystemService(WindowManager::class.java)
            wm=manager
            val density=ctx.resources.displayMetrics.density
            val label=TextView(ctx).apply{
                text="[테스트] 플로팅 창 정상"
                setTextColor(Color.WHITE);textSize=18f;gravity=Gravity.CENTER
                setPadding((20*density).toInt(),(12*density).toInt(),(20*density).toInt(),(12*density).toInt())
                background=GradientDrawable().apply{setColor(Color.BLACK);cornerRadius=12*density}
            }
            view=label
            val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT).apply{
                    gravity=Gravity.CENTER;alpha=.7f
                }
            manager.addView(label,params)
            status("플로팅 창 생성 성공 · 실제 표시 여부를 확인하세요")
            delay(5000)
            status("플로팅 창 생성 성공 · 테스트 종료")
        }catch(e:CancellationException){throw e}
        catch(e:Exception){status("플로팅 창 오류: "+e.javaClass.simpleName);android.util.Log.w("LyriLayer","Overlay probe failed",e)}
        finally{view?.let{v->runCatching{wm?.removeViewImmediate(v)}}}
    }
}
