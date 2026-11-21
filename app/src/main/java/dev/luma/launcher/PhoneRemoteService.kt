package dev.luma.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.concurrent.Executors

/** User-enabled local remote with a saved private link. Never enables debugging or root. */
class PhoneRemoteService:Service(){
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var server:PhoneTextSession?=null
    private var address=""
    @Volatile private var stopped=false
    private val check=object:Runnable{
        override fun run(){
            if(stopped)return
            worker.execute{
                runCatching{
                    FastPhoneInput.prepare(this@PhoneRemoteService)
                    val manager=getSystemService(ConnectivityManager::class.java)
                    val ip=manager.getLinkProperties(manager.activeNetwork)?.linkAddresses?.map{it.address}
                        ?.filterIsInstance<Inet4Address>()?.firstOrNull{!it.isLoopbackAddress && !it.isLinkLocalAddress}
                    if(ip==null){server?.close();server=null;currentUrl=null;address=""}
                    else if(server?.isOpen!=true || address!=ip.hostAddress){
                        server?.close();server=null;currentUrl=null
                        val control=PhoneRemoteControl.callback(this@PhoneRemoteService)?:error("Local controls unavailable")
                        val page=assets.open("phone-keyboard.html").bufferedReader().use{it.readText()}
                        val next=PhoneTextSession(ip,page,"Phone remote",2048,"",{},
                            lifetimeMillis=java.util.concurrent.TimeUnit.DAYS.toMillis(3650),onRemote=control,
                            port=19767,pairingToken=token(this@PhoneRemoteService),textTarget={PhoneRemoteControl.textTarget()},
                            icon=assets.open("luma-remote-icon.png").use{it.readBytes()},onInputReady={FastPhoneInput.ready()},onFocusNavigation={FastPhoneInput.usesNativeFocus()},onMagneticTarget={PhoneMouseCursor.magneticTarget()},onApps={PhoneRemoteControl.apps(this@PhoneRemoteService)},onLaunch={PhoneRemoteControl.launch(this@PhoneRemoteService,it)},onConnected={PhoneRemoteControl.connected()},onPointer={UniversalPointer.settings(this@PhoneRemoteService,it)})
                        if(stopped)next.close() else {
                            server=next;currentUrl=next.url;address=ip.hostAddress.orEmpty()
                            runCatching{PhoneRemoteControl.apps(this@PhoneRemoteService)}
                        }
                    }
                }.onFailure{android.util.Log.w("LumaPhoneRemote","Remote listener retry",it)}
                main.post{if(!stopped)main.postDelayed(this,10000)}
            }
        }
    }
    override fun onCreate(){
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel("luma-phone-remote","Phone remote",NotificationManager.IMPORTANCE_LOW))
        @Suppress("DEPRECATION")
        val builder=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,"luma-phone-remote")else Notification.Builder(this)
        val notification=builder.setSmallIcon(android.R.drawable.ic_menu_send).setContentTitle("Luma phone remote")
            .setContentText("Ready for your saved phone shortcut on the same Wi-Fi").setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(909,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)else startForeground(909,notification)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(!enabled(this)){stopSelf();return START_NOT_STICKY}
        main.removeCallbacks(check);main.post(check);return START_STICKY
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){stopped=true;main.removeCallbacksAndMessages(null);server?.close();server=null;currentUrl=null;UniversalPointer.close();FastPhoneInput.close();worker.shutdownNow();super.onDestroy()}
    companion object {
        @Volatile private var currentUrl:String?=null
        private fun prefs(context:Context)=context.getSharedPreferences("phone-remote-private",Context.MODE_PRIVATE)
        fun enabled(context:Context)=prefs(context).getBoolean("enabled",false)
        @Synchronized private fun token(context:Context):String{
            val data=prefs(context)
            return data.getString("token",null)?:ByteArray(24).also{SecureRandom().nextBytes(it)}
                .joinToString(""){"%02x".format(it.toInt() and 255)}.also{data.edit().putString("token",it).commit()}
        }
        fun url(context:Context):String?{
            if(!enabled(context))return null
            currentUrl?.let{return it}
            val manager=context.getSystemService(ConnectivityManager::class.java)
            val address=manager.getLinkProperties(manager.activeNetwork)?.linkAddresses?.map{it.address}
                ?.filterIsInstance<Inet4Address>()?.firstOrNull{!it.isLoopbackAddress && !it.isLinkLocalAddress}
                ?:return null
            return "http://${address.hostAddress}:19767/#${token(context)}"
        }
        fun enable(context:Context){prefs(context).edit().putBoolean("enabled",true).apply();resume(context)}
        fun disable(context:Context){prefs(context).edit().putBoolean("enabled",false).remove("token").apply();context.stopService(Intent(context,PhoneRemoteService::class.java))}
        fun resume(context:Context){if(enabled(context))runCatching{
            val intent=Intent(context,PhoneRemoteService::class.java)
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent)else context.startService(intent)
        }.onFailure{android.util.Log.w("LumaPhoneRemote","Remote startup deferred",it)}}
    }
}
