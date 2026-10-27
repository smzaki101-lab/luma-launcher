package dev.luma.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Small disk-first image cache; never hotlinks Trakt artwork into a widget. */
class TraktImageCache(context:Context){
    private val directory=File(context.cacheDir,"trakt-art").apply{mkdirs()}
    private val memory=object:LruCache<String,Bitmap>(8*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
    @Synchronized fun load(url:String,maxEdge:Int=640):Bitmap?{
        if(url.startsWith("file:")){
            val path=runCatching{java.io.File(java.net.URI(url)).canonicalFile}.getOrNull()?:return null
            val allowed=File(directory.parentFile?.parentFile,"files/media-resume").canonicalFile
            if(!path.path.startsWith(allowed.path+File.separator))return null
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(path.path,bounds)
            var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>maxEdge.coerceIn(320,1280))sample*=2
            return BitmapFactory.decodeFile(path.path,BitmapFactory.Options().apply{inSampleSize=sample})
        }
        val host=runCatching{URL(url).host.lowercase()}.getOrNull()?:return null
        if(!url.startsWith("https://") || !(host.endsWith(".trakt.tv") || host.endsWith(".mzstatic.com") ||
            host=="image.tmdb.org" || host.endsWith(".themoviedb.org") || host.endsWith(".media-amazon.com") ||
            host.endsWith(".ssl-images-amazon.com") || host.endsWith(".strem.io") ||
            host=="images.metahub.space" || host=="i.ytimg.com" || host=="img.youtube.com" || host=="i.scdn.co"))return null
        val edge=maxEdge.coerceIn(320,1280)
        val memoryKey="$url:$edge"
        memory.get(memoryKey)?.let{return it}
        val name=MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)}
        val file=File(directory,name)
        if(!file.exists()){
            val conn=URL(url).openConnection() as HttpURLConnection
            try{
                conn.connectTimeout=7000;conn.readTimeout=7000
                if(conn.responseCode!=200)return null
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                conn.inputStream.use{input->while(out.size()<2*1024*1024){val read=input.read(buffer,0,minOf(buffer.size,2*1024*1024-out.size()));if(read<0)break;out.write(buffer,0,read)}}
                file.writeBytes(out.toByteArray())
                directory.listFiles()?.sortedByDescending{it.lastModified()}?.drop(36)?.forEach{it.delete()}
            }finally{conn.disconnect()}
        }
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.path,options)
        var sample=1;while(maxOf(options.outWidth,options.outHeight)/sample>edge)sample*=2
        val bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})?:return null
        memory.put(memoryKey,bitmap);return bitmap
    }
    fun clear(){memory.evictAll();directory.listFiles()?.forEach{it.delete()}}
    fun trim(){memory.evictAll()}
}
