package dev.luma.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Opt-in P6 listener recovery through the projector's existing loopback debug transport. */
class LocalMenuService:Service(){
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val adb=LoopbackAdb()
    private var prepared=false
    private var stopping=false
    private var lastReady:Boolean?=null
    private val repair=Runnable{
        if(stopping || !enabled(this)){stopSelf();return@Runnable}
        worker.execute{
            var delay=20000L
            val result=runCatching{
                if(adb.request("shell:id -u").trim()!="0"){
                    runCatching{adb.request("root:")};delay=2000L
                    throw IOException("Waiting for root-capable local debugging")
                }
                if(!prepared)prepareListener()
                val state=adb.request("shell:$CONTROL check").trim()
                if(state!="running")check(adb.request("shell:$CONTROL start && printf LUMA_MENU_STARTED","LUMA_MENU_STARTED").trim().endsWith("LUMA_MENU_STARTED"))
            }
            main.post{
                if(!stopping){
                    val ready=result.isSuccess
                    if(lastReady!=ready){
                        lastReady=ready;getSystemService(NotificationManager::class.java).notify(907,notification(ready))
                        Log.i("LumaLocalMenu",if(ready)"Projector-only Menu listener is running" else "Waiting for projector-local debugging")
                        result.exceptionOrNull()?.let{Log.w("LumaLocalMenu","Local Menu setup deferred",it)}
                    }
                    scheduleRepair(delay)
                }
            }
        }
    }
    private fun scheduleRepair(delay:Long){main.removeCallbacks(repair);main.postDelayed(repair,delay)}
    private fun prepareListener(){
        val folder=File(filesDir,"menu-helper").apply{mkdirs()}
        val names=listOf("luma-menu-bridge.sh","luma-menu-control.sh")
        names.forEach{name->assets.open("p6-menu-bridge/$name").use{input->File(folder,name).outputStream().use{input.copyTo(it)}}}
        fun quoted(file:File)="'"+file.absolutePath.replace("'","'\\''")+"'"
        val command=buildString{
            append("set -e; mkdir -p $STATE; chmod 700 $STATE; ")
            append("if [ -f $STATE/luma-menu-control.sh ]; then $CONTROL stop; fi; ")
            names.forEach{name->append("cp ${quoted(File(folder,name))} $STATE/$name; chmod 700 $STATE/$name; ")}
            append("$CONTROL start; printf LUMA_MENU_INITIALIZED")
        }
        check(adb.request("shell:$command","LUMA_MENU_INITIALIZED").trim().endsWith("LUMA_MENU_INITIALIZED"))
        prepared=true
    }
    private fun notification(ready:Boolean):Notification{
        val settings=Intent(this,LauncherActivity::class.java).apply{
            action=LauncherActivity.ACTION_GLOBAL_CONTROL;putExtra(LauncherActivity.EXTRA_GLOBAL_TARGET,"global_menu_settings")
        }
        @Suppress("DEPRECATION")
        val builder=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,CHANNEL)else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle(getString(R.string.local_menu_title))
            .setContentText(getString(if(ready)R.string.local_menu_notification else R.string.local_menu_waiting)).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this,907,settings,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
    }
    override fun onCreate(){
        super.onCreate()
        if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,getString(R.string.local_menu_channel),NotificationManager.IMPORTANCE_LOW))
        val notification=notification(false)
        if(Build.VERSION.SDK_INT>=34)startForeground(907,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)else startForeground(907,notification)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==DISABLE){
            prefs(this).edit().putBoolean("enabled",false).apply();stopping=true;main.removeCallbacks(repair)
            worker.execute{runCatching{adb.request("shell:$CONTROL stop")};main.post{stopSelf()}}
            return START_NOT_STICKY
        }
        if(intent?.action==ENABLE)prefs(this).edit().putBoolean("enabled",true).apply()
        if(!enabled(this)){stopSelf();return START_NOT_STICKY}
        main.removeCallbacks(repair);main.post(repair);return START_STICKY
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){stopping=true;main.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy()}
    companion object {
        private const val CHANNEL="luma-local-menu"
        private const val STATE="/data/local/luma-menu"
        private const val CONTROL="LUMA_MENU_DIR=$STATE sh $STATE/luma-menu-control.sh"
        private const val ENABLE="dev.luma.launcher.ENABLE_LOCAL_MENU"
        private const val DISABLE="dev.luma.launcher.DISABLE_LOCAL_MENU"
        private fun prefs(context:Context)=context.getSharedPreferences("local-menu",Context.MODE_PRIVATE)
        fun enabled(context:Context)=prefs(context).getBoolean("enabled",false)
        private fun start(context:Context,intent:Intent){if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent)else context.startService(intent)}
        fun enable(context:Context){start(context,Intent(context,LocalMenuService::class.java).setAction(ENABLE))}
        fun disable(context:Context){start(context,Intent(context,LocalMenuService::class.java).setAction(DISABLE))}
        fun resumeIfEnabled(context:Context){if(enabled(context))runCatching{start(context,Intent(context,LocalMenuService::class.java))}.onFailure{Log.w("LumaLocalMenu","Startup deferred until Luma opens",it)}}
    }
}

class LocalMenuBootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)){
            LocalMenuService.resumeIfEnabled(context)
            StremioAutoService.resume(context)
        }
    }
}
