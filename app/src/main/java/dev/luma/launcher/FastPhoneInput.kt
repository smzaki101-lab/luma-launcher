package dev.luma.launcher

import android.content.Context
import java.io.BufferedReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/** One warm input process and connection instead of a new Android VM for every button. */
object FastPhoneInput {
    @Volatile private var available=false
    @Volatile private var lastAck=0L
    fun ready()=available && android.os.SystemClock.elapsedRealtime()-lastAck<20000
    @Volatile private var focusNavigation=false
    fun usesNativeFocus()=focusNavigation
    private var socket:Socket?=null
    private var reader:BufferedReader?=null
    private var output:OutputStream?=null
    @Synchronized fun prepare(context:Context):Boolean{
        if(runCatching{send("ping")}.getOrDefault(false))return true
        close()
        return runCatching{
            val token=ByteArray(24).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it.toInt() and 255)}
            val port=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use{it.localPort}
            val path="'"+context.applicationInfo.sourceDir.replace("'","'\\''")+"'"
            val size=context.resources.displayMetrics
            val command="CLASSPATH=$path nohup app_process /system/bin dev.luma.launcher.PhoneInputBridge $token $port ${size.widthPixels} ${size.heightPixels} >/data/local/tmp/luma-phone-input.log 2>&1 </dev/null & printf LUMA_INPUT_STARTED"
            LoopbackAdb().request("shell:$command","LUMA_INPUT_STARTED")
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4)
            while(socket==null && System.nanoTime()<deadline){
                runCatching{
                    val next=Socket().apply{tcpNoDelay=true;connect(InetSocketAddress("127.0.0.1",port),200);soTimeout=2000}
                    socket=next;reader=next.getInputStream().bufferedReader();output=next.getOutputStream()
                    output!!.write((token+"\n").toByteArray());output!!.flush()
                }.onFailure{Thread.sleep(40)}
            }
            check(send("ping"));true
        }.getOrElse{close();android.util.Log.w("LumaPhoneRemote","Fast input unavailable",it);false}
    }
    @Synchronized fun command(context:Context,value:String):Boolean{
        if(socket==null && !prepare(context))return false
        return runCatching{send(value)}.getOrElse{close();false}
    }
    private fun send(value:String):Boolean{
        val target=output?:return false
        target.write((value+"\n").toByteArray());target.flush()
        val ack=reader?.readLine()
        val ok=ack=="OK" || ack=="OK FOCUS"
        focusNavigation=ack=="OK FOCUS"
        available=ok
        if(ok)lastAck=android.os.SystemClock.elapsedRealtime()
        return ok
    }
    @Synchronized fun close(){available=false;focusNavigation=false;PhoneMouseCursor.close();runCatching{socket?.close()};socket=null;reader=null;output=null}
}
