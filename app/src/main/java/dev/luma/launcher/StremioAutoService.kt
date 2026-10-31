package dev.luma.launcher

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.*
import java.util.UUID
import java.util.concurrent.*

/** Opt-in, loopback-only addon. No remote shell, player interception or content relay. */
class StremioAutoService:Service(){
    private var server:ServerSocket?=null
    private val handlers=ThreadPoolExecutor(2,2,0L,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(4))
    private val network=Executors.newFixedThreadPool(4)
    @Volatile private var running=false
    @Volatile private var speed=10.0
    @Volatile private var measuredAt=0L
    private var sampling:Future<*>?=null
    private val cache=ConcurrentHashMap<String,Pair<Long,String>>()
    override fun onCreate(){
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel(CHANNEL,"Stremio Auto",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,919,Intent(this,LauncherActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val builder=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,CHANNEL)else Notification.Builder(this)
        val notification=builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Stremio Auto is available")
            .setContentText("Source recommendations run on this projector").setContentIntent(open).setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(919,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)else startForeground(919,notification)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(!enabled(this)){stopSelf();return START_NOT_STICKY}
        if(server==null)try {
            val listener=ServerSocket().apply{reuseAddress=true;bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),PORT),4)}
            server=listener;running=true
            Thread({while(running)try{
                val socket=listener.accept().apply{soTimeout=5000}
                try{handlers.execute{socket.use{runCatching{serve(it)}}}}catch(_:RejectedExecutionException){socket.close()}
            }catch(_:java.io.IOException){break}},"Stremio Auto listener").apply{isDaemon=true;start()}
        }catch(_:java.io.IOException){prefs(this).edit().putString("status","Local port unavailable; disable and enable Auto to retry.").apply();stopSelf();return START_NOT_STICKY}
        refreshSpeed()
        return START_STICKY
    }
    @Synchronized private fun refreshSpeed(){
        if(System.currentTimeMillis()-measuredAt<600000 || sampling?.isDone==false)return
        sampling=network.submit{
            // A bounded HTTP sample on the projector, not on a remote addon host.
            val start=System.nanoTime()
            val result=runCatching{request("https://speed.cloudflare.com/__down?bytes=2000000",2000000,6500)}
            result.onSuccess{bytes->
                if(bytes.size>=100000){speed=(bytes.size*8.0/((System.nanoTime()-start)/1e9)/1e6).coerceIn(1.0,1000.0);measuredAt=System.currentTimeMillis()}
            }
            prefs(this).edit().putString("speedStatus",if(result.isSuccess && measuredAt>0)"HTTP estimate: %.1f Mbps".format(java.util.Locale.US,speed)
                else "Speed sample unavailable · conservative 10 Mbps estimate").apply()
        }
    }
    private fun serve(socket:Socket){
        val input=socket.getInputStream();val bytes=java.io.ByteArrayOutputStream()
        while(bytes.size()<8192){val value=input.read();if(value<0)return;bytes.write(value)
            val b=bytes.toByteArray();if(b.size>=4 && b.takeLast(4)==listOf<Byte>(13,10,13,10))break}
        val header=bytes.toString("US-ASCII")
        if(!header.endsWith("\r\n\r\n")){reply(socket,400,"{}");return}
        val first=header.substringBefore("\r\n").split(' ')
        if(first.size!=3){reply(socket,400,"{}");return}
        if(first[0]=="OPTIONS"){reply(socket,200,"{}");return}
        if(first[0]!="GET"){reply(socket,405,"{}");return}
        val prefix="/${token(this)}/"
        val decoded=runCatching{URLDecoder.decode(first[1].substringBefore('?'),"UTF-8")}.getOrDefault("")
        if(!decoded.startsWith(prefix)){reply(socket,404,"{}");return}
        val path=decoded.removePrefix(prefix)
        android.util.Log.i("LumaAuto","Local request: "+path.split('/').take(2).joinToString("/").take(80))
        when{
            path=="manifest.json"->reply(socket,200,manifest().toString())
            path=="status.json"->reply(socket,200,JSONObject().put("status",prefs(this).getString("status","Ready"))
                .put("speed",prefs(this).getString("speedStatus","Measuring connection…")).toString())
            Regex("^stream/(movie|series)/(tt[0-9]+(?::[0-9]+:[0-9]+)?).json$").matches(path)->{
                val parts=path.split('/');val id=parts[2].removeSuffix(".json")
                val key="${parts[1]}/$id"
                val cached=cache[key]
                val body=if(cached!=null && System.currentTimeMillis()-cached.first<30000)cached.second else streams(parts[1],id).also{
                    if(cache.size>30)cache.clear();cache[key]=System.currentTimeMillis() to it}
                reply(socket,200,body)
            }
            else->reply(socket,404,"{}")
        }
    }
    private fun streams(type:String,id:String):String{
        refreshSpeed()
        val sources=readSources(this)
        val metadata=network.submit<Double?>{
            val meta=JSONObject(String(request("https://v3-cinemeta.strem.io/meta/$type/${id.substringBefore(':')}.json",2_000_000,2500),Charsets.UTF_8)).optJSONObject("meta")
            Regex("[0-9]+(?:\\.[0-9]+)?").find(meta?.optString("runtime").orEmpty())?.value?.toDoubleOrNull()?.takeIf{it>0}
        }
        val calls=sources.map{source->network.submit<JSONArray>{
            JSONObject(String(request(source.removeSuffix("/manifest.json")+"/stream/$type/$id.json",2_000_000,6500),Charsets.UTF_8)).optJSONArray("streams")?:JSONArray()
        }}
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        val all=mutableListOf<JSONObject>();var failures=0
        for(call in calls)try{
            val rows=call.get((deadline-System.nanoTime()).coerceAtLeast(1),TimeUnit.NANOSECONDS)
            for(i in 0 until minOf(rows.length(),150))rows.optJSONObject(i)?.let{all.add(it)}
        }catch(_:Exception){failures++;call.cancel(true)}
        val duration=try{metadata.get(100,TimeUnit.MILLISECONDS)}catch(_:Exception){metadata.cancel(true);null}
        val valid=all.filter { stream ->
            val hash=stream.optString("infoHash")
            val url=stream.optString("url")
            hash.matches(Regex("[a-fA-F0-9]{40}")) || runCatching{URI(url).let{it.scheme=="https" && it.host!=null && it.userInfo==null}}.getOrDefault(false)
        }.distinctBy{it.optString("infoHash").ifBlank{it.optString("url")}+":"+it.optString("fileIdx")}
        val candidates=valid.mapIndexed{index,row->AutoStreamPolicy.candidate(index,
            row.optString("name")+"\n"+row.optString("title")+"\n"+row.optString("description"),duration)}
        val best=AutoStreamPolicy.ranked(candidates,speed).firstOrNull()
        val response=JSONArray()
        if(best!=null){
            val stream=JSONObject(valid[best.index].toString())
            val sourceName=stream.optString("name").replace('\n',' ')
            val detail=stream.optString("description").ifBlank{stream.optString("title")}
            stream.put("name","Auto · Best available")
            val explanation="Estimated choice · ${best.height.takeIf{it>0}?.let{"${it}p"}?:"quality unknown"} · $sourceName\n"+
                "Connection budget %.1f Mbps · reported peers %s\n".format(java.util.Locale.US,speed*.55,best.seeds?.toString()?:"unknown")+
                "No speed guarantee; original source choices remain below.\n$detail"
            // Stremio treats title as an alias for description; both together fail deserialization.
            stream.remove("title")
            stream.put("description",explanation)
            // Do not reuse another provider's binge group for this recommendation policy.
            stream.optJSONObject("behaviorHints")?.remove("bingeGroup")
            response.put(stream)
        }
        val status=if(best==null)"No compatible sources available. Use the original source list."
            else "Recommended ${best.height}p from ${valid.size} unique sources · $failures provider failures"
        prefs(this).edit().putString("status",status).apply()
        return JSONObject().put("streams",response).put("cacheMaxAge",0).toString()
    }
    private fun reply(socket:Socket,code:Int,body:String){
        val data=body.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply{
            write(("HTTP/1.1 $code ${if(code==200)"OK" else "Error"}\r\nContent-Type: application/json; charset=utf-8\r\n"+
                "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, OPTIONS\r\nCache-Control: no-store\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n").toByteArray())
            write(data);flush()
        }
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){running=false;runCatching{server?.close()};handlers.shutdownNow();network.shutdownNow();super.onDestroy()}
    companion object {
        private const val PORT=19765
        private const val CHANNEL="luma-stremio-auto"
        private fun prefs(c:Context)=c.getSharedPreferences("stremio_auto",Context.MODE_PRIVATE)
        fun enabled(c:Context)=prefs(c).getBoolean("enabled",false)
        @Synchronized private fun token(c:Context):String=prefs(c).getString("token",null)?:UUID.randomUUID().toString().also{prefs(c).edit().putString("token",it).commit()}
        fun url(c:Context)="http://127.0.0.1:$PORT/${token(c)}/manifest.json"
        fun status(c:Context)=prefs(c).getString("status","Ready").orEmpty()+"\n"+prefs(c).getString("speedStatus","Speed not sampled yet")
        fun readSources(c:Context):List<String> = runCatching{
            val array=JSONArray(File(c.filesDir,"stremio-auto-sources.json").readText())
            (0 until minOf(array.length(),4)).map{array.getString(it)}.filter{source->runCatching{
                URI(source).let{it.scheme=="https" && !it.host.isNullOrBlank() && it.userInfo==null && it.path.endsWith("/manifest.json") && it.fragment==null && it.query==null}
            }.getOrDefault(false)}
        }.getOrDefault(emptyList())
        fun saveSources(c:Context,text:String){
            val list=text.lines().map{it.trim()}.filter{it.isNotEmpty()}.distinct()
            require(list.size in 1..4 && list.all{runCatching{URI(it).let{uri->uri.scheme=="https" && uri.host!=null && uri.userInfo==null && uri.path.endsWith("/manifest.json") && uri.fragment==null && uri.query==null}}.getOrDefault(false)})
            File(c.filesDir,"stremio-auto-sources.json").writeText(JSONArray(list).toString())
        }
        fun setEnabled(c:Context,on:Boolean){
            prefs(c).edit().putBoolean("enabled",on).apply()
            if(on)resume(c)else c.stopService(Intent(c,StremioAutoService::class.java))
        }
        fun resume(c:Context){if(enabled(c))runCatching{
            val intent=Intent(c,StremioAutoService::class.java)
            if(Build.VERSION.SDK_INT>=26)c.startForegroundService(intent)else c.startService(intent)
        }}
        private fun request(address:String,limit:Int,timeout:Int):ByteArray {
            val connection=URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout=timeout;connection.readTimeout=timeout
            connection.setRequestProperty("User-Agent","Luma-Stremio-Auto/0.1")
            connection.setRequestProperty("Accept","application/json, */*")
            try{
                check(connection.responseCode==200)
                connection.inputStream.use{input->
                    val result=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
                    val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout.toLong())
                    while(result.size()<limit && System.nanoTime()<deadline){
                        val count=input.read(buffer,0,minOf(buffer.size,limit-result.size()));if(count<0)break;result.write(buffer,0,count)
                    }
                    return result.toByteArray()
                }
            }finally{connection.disconnect()}
        }
        private fun manifest()=JSONObject().put("id","dev.luma.stremio.auto").put("version","0.1.0").put("name","Luma Auto")
            .put("description","On-projector stream recommendations. Estimated connection fit, up to 1080p SDR; no interruption-free guarantee.")
            .put("types",JSONArray(listOf("movie","series"))).put("catalogs",JSONArray())
            .put("resources",JSONArray().put(JSONObject().put("name","stream").put("types",JSONArray(listOf("movie","series")))
                .put("idPrefixes",JSONArray(listOf("tt")))))
    }
}
