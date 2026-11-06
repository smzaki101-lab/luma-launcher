package dev.luma.launcher

import kotlin.math.ln

/** Estimates suitability; reported peers and an HTTP speed sample cannot promise torrent throughput. */
internal object AutoStreamPolicy {
    data class Candidate(val index:Int,val label:String,val height:Int,val seeds:Int?,val mbps:Double,val hdr:Boolean,val poorCopy:Boolean)
    fun candidate(index:Int,label:String,durationMinutes:Double?):Candidate {
        val lower=label.lowercase()
        val height=when {Regex("\\b(2160p|4k)\\b").containsMatchIn(lower)->2160
            "1080" in lower->1080;"720" in lower->720;"480" in lower->480;else->0}
        val seeds=Regex("(?:👤|seeds?[: ]*)\\s*([0-9]+)",RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull()
        val size=Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(GB|MB|GiB|MiB)\\b",RegexOption.IGNORE_CASE).find(label)
        val bytes=size?.let{it.groupValues[1].toDouble()*if(it.groupValues[2].startsWith("G",true))1e9 else 1e6}
        val bitrate=if(bytes!=null && durationMinutes!=null && durationMinutes>0)bytes*8/(durationMinutes*60*1e6)
            else when(height){2160->30.0;1080->9.0;720->4.0;480->2.0;else->6.0}
        return Candidate(index,label,height,seeds,bitrate,"hdr" in lower || "dolby vision" in lower || Regex("\\bdv\\b").containsMatchIn(lower),
            Regex("\\b(cam|hdcam|telesync|hdts)\\b").containsMatchIn(lower))
    }
    fun ranked(items:List<Candidate>,connectionMbps:Double):List<Candidate> {
        val budget=connectionMbps.coerceIn(1.0,1000.0)*.55
        val supported=items.filter{it.height<=1080 && !it.hdr}
        if(supported.isEmpty())return emptyList()
        val viable=if(supported.any{(it.seeds?:1)>0})supported.filter{(it.seeds?:1)>0}else supported
        return viable.sortedByDescending { item ->
            val fit=if(item.mbps<=budget)100.0 else -100.0*item.mbps/budget
            fit+when(item.height){1080->18.0;720->12.0;480->5.0;else->0.0}+
                ln((item.seeds?:1).coerceAtLeast(0)+1.0)*12.0-(if(item.poorCopy)200 else 0)
        }
    }
}
