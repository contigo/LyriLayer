package com.chaoseye.lyrilayer

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService

internal object FloatingPermissions {
    fun audioName()=if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun audioGranted(c:Context)=c.checkSelfPermission(audioName())==PackageManager.PERMISSION_GRANTED
    fun component(c:Context)=ComponentName(c,MusicListener::class.java)
    fun listenerGranted(c:Context):Boolean {
        val component=component(c)
        return if(Build.VERSION.SDK_INT>=27){
            runCatching{c.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)}.getOrDefault(false)
        }else{
            Settings.Secure.getString(c.contentResolver,"enabled_notification_listeners").orEmpty().split(":")
                .any{ComponentName.unflattenFromString(it)==component}
        }
    }
    fun listenerSettings(c:Context):Intent {
        if(Build.VERSION.SDK_INT>=30){
            val detail=Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,component(c).flattenToString())
            if(detail.resolveActivity(c.packageManager)!=null)return detail
        }
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
    fun overlaySettings(c:Context)=Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+c.packageName))
    fun appSettings(c:Context)=Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+c.packageName))
    fun rebind(c:Context){
        if(listenerGranted(c)&&!Playback.connected.value){
            runCatching{NotificationListenerService.requestRebind(component(c))}
        }
    }
}
