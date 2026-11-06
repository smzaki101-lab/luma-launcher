package dev.luma.launcher

import org.junit.Assert.*
import org.junit.Test

class AutoStreamPolicyTest {
    private fun item(label:String,index:Int=0)=AutoStreamPolicy.candidate(index,label,null)
    @Test fun slowConnectionChoosesSmallerStream(){
        assertEquals(720,AutoStreamPolicy.ranked(listOf(item("1080p 👤 100"),item("720p 👤 100",1)),10.0).first().height)
    }
    @Test fun fastConnectionCanChoose1080(){
        assertEquals(1080,AutoStreamPolicy.ranked(listOf(item("1080p 👤 100"),item("720p 👤 100",1)),50.0).first().height)
    }
    @Test fun avoidsDeadTorrentAndHdr(){
        val candidates=listOf(item("1080p 👤 0"),item("1080p HDR 👤 100",1),item("720p 👤 20",2))
        assertEquals(2,AutoStreamPolicy.ranked(candidates,50.0).single().index)
    }
    @Test fun noIncompatibleFallback(){
        assertTrue(AutoStreamPolicy.ranked(listOf(item("4k 👤 100")),100.0).isEmpty())
    }
    @Test fun runtimeAndSizeEstimateBitrate(){
        val candidate=AutoStreamPolicy.candidate(0,"1080p 👤 25 💾 3 GB",100.0)
        assertEquals(4.0,candidate.mbps,.01)
        assertEquals(25,candidate.seeds)
    }
    @Test fun prefersAvailablePeersAndAvoidsCameraCopies(){
        assertEquals(1,AutoStreamPolicy.ranked(listOf(item("1080p HDCAM 👤 90"),item("720p 👤 25",1)),50.0).first().index)
    }
}
