package dev.luma.launcher

import java.net.URI
import java.security.MessageDigest

internal object MediaResumePolicy {
    val labels=mapOf("com.google.android.youtube.tv" to "YouTube",
        "io.gh.reisxd.tizentube.cobalt" to "YouTube", "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.spotify.tv.android" to "Spotify", "com.spotify.music" to "Spotify")
    fun id(pkg:String,mediaId:String,title:String,artist:String):String{
        val identity=mediaId.ifBlank{"$title\n$artist"}
        return "media:"+MessageDigest.getInstance("SHA-256").digest("$pkg\n$identity".toByteArray())
            .joinToString(""){"%02x".format(it)}
    }
    fun progress(position:Long,duration:Long):Float?=if(duration>0 && position>=0)
        (position.coerceAtMost(duration)*100f/duration).coerceIn(0f,100f)else null
    fun reportedLink(pkg:String,mediaId:String,mediaUri:String,artUri:String,position:Long):String{
        link(pkg,mediaUri).ifBlank{link(pkg,mediaId)}.takeIf{it.isNotBlank()}?.let{return it}
        if(labels[pkg]=="YouTube"){
            val image=runCatching{URI(artUri)}.getOrNull()
            val imageId=if(image?.scheme=="https" && image.host in setOf("i.ytimg.com","img.youtube.com"))
                Regex("^/(?:vi|vi_webp)/([A-Za-z0-9_-]{11})/").find(image.path.orEmpty())?.groupValues?.get(1)else null
            val id=mediaId.takeIf{it.matches(Regex("[A-Za-z0-9_-]{11}"))}?:imageId?:return ""
            return "https://www.youtube.com/watch?v=$id"+if(position>0)"&t=${position/1000}" else ""
        }
        if(labels[pkg]=="Prime Video" && mediaId.matches(Regex("B[A-Z0-9]{9}")))
            return "https://app.primevideo.com/detail?gti=$mediaId"
        return ""
    }
    fun link(pkg:String,candidate:String):String{
        if(pkg !in labels || candidate.length>2048)return ""
        val uri=runCatching{URI(candidate)}.getOrNull()?:return ""
        if(uri.userInfo!=null)return ""
        if(labels[pkg]=="Spotify" && candidate.matches(Regex("spotify:(track|episode|album|playlist):[A-Za-z0-9]+")))return candidate
        if(uri.scheme!="https")return ""
        val host=uri.host?.lowercase()?:return ""
        val accepted=when(labels[pkg]){
            "YouTube"->host in setOf("youtube.com","www.youtube.com","youtu.be","m.youtube.com")
            "Spotify"->host=="open.spotify.com"
            "Prime Video"->host in setOf("app.primevideo.com","www.primevideo.com","watch.amazon.com","watch.amazon.co.uk")
            else->false
        }
        return if(accepted)candidate else ""
    }
}
