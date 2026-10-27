package dev.luma.launcher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Media-session access only. Notification messages, bodies and actions are never read. */
class MediaResumeService:NotificationListenerService(){
    private val callbacks=mutableMapOf<MediaController,MediaController.Callback>()
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val artworkVersions=mutableMapOf<String,Int>()
    private val manager get()=getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val changed=MediaSessionManager.OnActiveSessionsChangedListener{observe(it.orEmpty())}
    private val tick=object:Runnable{override fun run(){callbacks.keys.toList().filter{it.playbackState?.state==PlaybackState.STATE_PLAYING}.forEach{capture(it)};main.postDelayed(this,15000)}}
    override fun onListenerConnected(){
        super.onListenerConnected()
        stopSelf() // Android's listener binding owns the lifetime after first-use initialization.
        runCatching{manager.addOnActiveSessionsChangedListener(changed,MediaResumeSource.component(this),main)
            observe(manager.getActiveSessions(MediaResumeSource.component(this)))}
        main.removeCallbacks(tick);main.post(tick)
    }
    private fun observe(controllers:List<MediaController>){
        callbacks.forEach{(controller,callback)->controller.unregisterCallback(callback)};callbacks.clear()
        controllers.filter{it.packageName in MediaResumePolicy.labels}.forEach{controller->
            val callback=object:MediaController.Callback(){
                override fun onMetadataChanged(metadata:MediaMetadata?){capture(controller)}
                override fun onPlaybackStateChanged(state:PlaybackState?){capture(controller)}
            }
            callbacks[controller]=callback;controller.registerCallback(callback,main);capture(controller)
        }
    }
    private fun capture(controller:MediaController){
        val pkg=controller.packageName
        val state=controller.playbackState?:return
        if(state.state !in setOf(PlaybackState.STATE_PLAYING,PlaybackState.STATE_PAUSED))return
        if(state.state==PlaybackState.STATE_PAUSED && state.position<=0)return
        val metadata=controller.metadata
        val reportedTitle=metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.take(300)?.takeIf{it.isNotBlank()}
        // Prime on this P6 reports real playback but leaves metadata empty.
        // Keep an honest app-session checkpoint, never infer a title from recommendations.
        val primeSession=reportedTitle==null && pkg=="com.amazon.amazonvideo.livingroom"
        val title=reportedTitle?:if(primeSession)"Continue in Prime Video" else return
        val artist=metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().take(300)
        val mediaId=metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val id=MediaResumePolicy.id(pkg,mediaId,title,artist)
        val duration=if(primeSession)0L else metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?:0L
        val elapsed=if(state.state==PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime>0)
            ((android.os.SystemClock.elapsedRealtime()-state.lastPositionUpdateTime)*state.playbackSpeed).toLong().coerceAtLeast(0)else 0L
        val progress=MediaResumePolicy.progress(if(state.position<0)-1 else state.position+elapsed,duration)
        val prefs=Preferences(this);if(prefs.number("continueEnabled",1)!=1)return
        val profile=prefs.profile
        val spotify=MediaResumePolicy.labels[pkg]=="Spotify"
        // Spotify may publish its app logo as DISPLAY_ICON before the album cover arrives.
        // Never save that temporary icon as a track's artwork.
        val art=metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?:metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?:if(spotify)null else metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artUri=(metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?:metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?:if(spotify)null else metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)).orEmpty()
        val link=MediaResumePolicy.reportedLink(pkg,mediaId,metadata?.description?.mediaUri?.toString().orEmpty(),artUri,state.position)
        worker.execute{
            if(!MediaResumeSource.enabled(this@MediaResumeService))return@execute
            runCatching{
                val folder=File(filesDir,"media-resume/p$profile").apply{mkdirs()}
                val file=File(folder,id.removePrefix("media:")+".jpg")
                if(art!=null && artworkVersions[file.path]!=art.generationId){
                    val scale=minOf(1f,960f/maxOf(art.width,art.height))
                    val scaled=android.graphics.Bitmap.createScaledBitmap(art,(art.width*scale).toInt().coerceAtLeast(1),(art.height*scale).toInt().coerceAtLeast(1),true)
                    val temporary=File(folder,file.name+".tmp")
                    try{temporary.outputStream().use{check(scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG,88,it))}
                        check(temporary.renameTo(file));artworkVersions[file.path]=art.generationId
                    }finally{temporary.delete();if(scaled!==art)scaled.recycle()}
                }
                val image=if(file.exists())android.net.Uri.fromFile(file).toString()else MediaResumeSource.artLink(pkg,artUri)
                val listening=MediaResumePolicy.labels[pkg]=="Spotify"
                val detail=if(primeSession)"Open Prime Video · Title not provided by Prime" else
                    listOf(if(listening)"Continue listening" else "Continue watching",artist.takeIf{it.isNotBlank()}).filterNotNull().joinToString(" · ")
                val item=ContinueItem(id,title,detail,progress,System.currentTimeMillis().toString(),image,"",pkg,
                    MediaResumePolicy.labels[pkg]!!,link,"Local media session")
                MediaResumeSource.save(this@MediaResumeService,profile,item)
            }
        }
    }
    private fun stop(){main.removeCallbacks(tick);runCatching{manager.removeOnActiveSessionsChangedListener(changed)}
        callbacks.forEach{(controller,callback)->controller.unregisterCallback(callback)};callbacks.clear()}
    override fun onListenerDisconnected(){stop();stopSelf();super.onListenerDisconnected()}
    override fun onDestroy(){stop();worker.shutdownNow();super.onDestroy()}
}

object MediaResumeSource {
    data class MusicPlayback(val playing:Boolean,val canToggle:Boolean,val canSkip:Boolean,val progress:Float?)
    private fun musicController(context:Context,item:ContinueItem):MediaController?{
        if(!enabled(context) || MediaResumePolicy.labels[item.sourcePackage]!="Spotify")return null
        return runCatching{
            val manager=context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            manager.getActiveSessions(component(context)).firstOrNull{controller->
                if(controller.packageName!=item.sourcePackage)return@firstOrNull false
                val data=controller.metadata?:return@firstOrNull false
                MediaResumePolicy.id(controller.packageName,data.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
                    data.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                    data.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())==item.id
            }
        }.getOrNull()
    }
    fun musicPlayback(context:Context,item:ContinueItem):MusicPlayback?{
        val controller=musicController(context,item)?:return null
        val state=controller.playbackState?:return null
        val playing=state.state==PlaybackState.STATE_PLAYING
        val toggle=if(playing)PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
        val duration=controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?:0L
        val elapsed=if(playing && state.lastPositionUpdateTime>0)
            ((android.os.SystemClock.elapsedRealtime()-state.lastPositionUpdateTime)*state.playbackSpeed).toLong().coerceAtLeast(0)
            else 0L
        return MusicPlayback(playing,(state.actions and toggle)!=0L,
            (state.actions and PlaybackState.ACTION_SKIP_TO_NEXT)!=0L,
            MediaResumePolicy.progress(state.position+elapsed,duration))
    }
    fun controlMusic(context:Context,item:ContinueItem,skip:Boolean):Boolean{
        val controller=musicController(context,item)?:return false
        val state=controller.playbackState?:return false
        val action=if(skip)PlaybackState.ACTION_SKIP_TO_NEXT else
            if(state.state==PlaybackState.STATE_PLAYING)PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
        if((state.actions and action)==0L)return false
        return runCatching{
            if(skip)controller.transportControls.skipToNext()
            else if(action==PlaybackState.ACTION_PAUSE)controller.transportControls.pause()
            else controller.transportControls.play()
            true
        }.getOrDefault(false)
    }
    fun connect(context:Context){
        if(!enabled(context))return
        runCatching{context.startService(android.content.Intent(context,MediaResumeService::class.java))
            if(android.os.Build.VERSION.SDK_INT>=24)NotificationListenerService.requestRebind(component(context))}
    }
    fun component(context:Context)=ComponentName(context,MediaResumeService::class.java)
    fun enabled(context:Context)=Settings.Secure.getString(context.contentResolver,"enabled_notification_listeners")
        .orEmpty().split(':').mapNotNull{ComponentName.unflattenFromString(it)}.contains(component(context))
    fun read(context:Context):List<ContinueItem>{
        if(!enabled(context))return emptyList()
        return read(context,Preferences(context).profile)
    }
    private fun read(context:Context,profile:Int):List<ContinueItem> = runCatching{
        val array=JSONArray(context.getSharedPreferences("media_resume",Context.MODE_PRIVATE).getString("p$profile","[]"))
        (0 until minOf(array.length(),12)).map{array.getJSONObject(it)}.map{
            ContinueItem(it.getString("id"),it.getString("title"),it.optString("subtitle"),
                if(it.has("progress"))it.getDouble("progress").toFloat()else null,it.getString("time"),it.optString("image"),"",
                it.getString("package"),it.getString("label"),it.optString("link"),"Local media session")
        }.filter{it.sourcePackage in MediaResumePolicy.labels}
    }.getOrDefault(emptyList())
    @Synchronized fun save(context:Context,profile:Int,item:ContinueItem){
        // Capture must never change the user's active launcher profile.
        val items=(listOf(item)+read(context,profile).filter{it.id!=item.id}).groupBy{it.sourcePackage}.values
            .flatMap{group->group.take(if(MediaResumePolicy.labels[group.first().sourcePackage]=="Spotify")1 else 3)}
            .sortedByDescending{it.pausedAt.toLongOrNull()?:0}.take(12)
        val array=JSONArray()
        items.forEach{entry->array.put(JSONObject().put("id",entry.id).put("title",entry.title).put("subtitle",entry.subtitle)
            .put("progress",entry.progress).put("time",entry.pausedAt).put("image",entry.imageUrl)
            .put("package",entry.sourcePackage).put("label",entry.sourceLabel).put("link",entry.openUri))}
        context.getSharedPreferences("media_resume",Context.MODE_PRIVATE).edit().putString("p$profile",array.toString()).apply()
        val keep=items.map{android.net.Uri.parse(it.imageUrl).path}.toSet()
        File(context.filesDir,"media-resume/p$profile").listFiles()?.filter{it.path !in keep}?.forEach{it.delete()}
    }
    fun clear(context:Context){
        val prefs=Preferences(context)
        context.getSharedPreferences("media_resume",Context.MODE_PRIVATE).edit().remove("p${prefs.profile}").apply()
        File(context.filesDir,"media-resume/p${prefs.profile}").listFiles()?.forEach{it.delete()}
    }
    fun artLink(pkg:String,value:String):String{
        val uri=runCatching{java.net.URI(value)}.getOrNull()?:return ""
        val host=uri.host?.lowercase()?:return ""
        return if(uri.scheme=="https" && when(MediaResumePolicy.labels[pkg]){
            "YouTube"->host=="i.ytimg.com" || host=="img.youtube.com"
            "Spotify"->host=="i.scdn.co"
            "Prime Video"->host.endsWith(".media-amazon.com") || host.endsWith(".ssl-images-amazon.com")
            else->false
        })value else ""
    }
    fun resumeLive(context:Context,item:ContinueItem):Boolean{
        if(!enabled(context))return false
        return runCatching{
            val manager=context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controller=manager.getActiveSessions(component(context)).firstOrNull{controller->
                if(controller.packageName!=item.sourcePackage)return@firstOrNull false
                val data=controller.metadata?:return@firstOrNull false
                MediaResumePolicy.id(controller.packageName,data.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
                    data.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),data.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())==item.id
            }?:return false
            val activity=controller.sessionActivity?.takeIf{it.creatorPackage==item.sourcePackage}?:return false
            activity.send()
            if(controller.playbackState?.state==PlaybackState.STATE_PAUSED)controller.transportControls.play()
            true
        }.getOrDefault(false)
    }
}
