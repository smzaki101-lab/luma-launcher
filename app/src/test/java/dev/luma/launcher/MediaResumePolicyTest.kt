package dev.luma.launcher
import org.junit.Assert.*
import org.junit.Test
class MediaResumePolicyTest {
    @Test fun derivesOnlyValidatedProviderContentIds(){
        val youtube="com.google.android.youtube.tv"
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk&t=15",MediaResumePolicy.reportedLink(youtube,"abcdefghijk","","",15000))
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk",MediaResumePolicy.reportedLink(youtube,"","","https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg",0))
        assertEquals("",MediaResumePolicy.reportedLink(youtube,"../../escape","","https://evil.example/vi/abcdefghijk/test.jpg",0))
        assertEquals("https://app.primevideo.com/detail?gti=B012345678",MediaResumePolicy.reportedLink("com.amazon.amazonvideo.livingroom","B012345678","","",0))
    }
    @Test fun resumeLinksStayWithinTheReportedProvider(){
        val youtube="com.google.android.youtube.tv"
        assertEquals("https://www.youtube.com/watch?v=abc",MediaResumePolicy.link(youtube,"https://www.youtube.com/watch?v=abc"))
        for(url in listOf("https://youtube.com.evil.example/x","javascript:alert(1)","file:///x","https://open.spotify.com/track/x","https://user@youtube.com/x"))assertEquals("",MediaResumePolicy.link(youtube,url))
        assertEquals("spotify:track:abc123",MediaResumePolicy.link("com.spotify.tv.android","spotify:track:abc123"))
        assertEquals("",MediaResumePolicy.link("other.app","https://www.youtube.com/x"))
    }
    @Test fun identitiesSeparateAppsAndTitlesButIgnorePositionChanges(){
        assertEquals(MediaResumePolicy.id("app","id","A","artist"),MediaResumePolicy.id("app","id","B","artist"))
        assertNotEquals(MediaResumePolicy.id("app","id","A",""),MediaResumePolicy.id("other","id","A",""))
        assertNotEquals(MediaResumePolicy.id("app","","A",""),MediaResumePolicy.id("app","","B",""))
    }
    @Test fun progressUsesReportedDurationAndClampsBounds(){
        assertNull(MediaResumePolicy.progress(10,0))
        assertEquals(50f,MediaResumePolicy.progress(500,1000)!!,0.01f)
        assertEquals(100f,MediaResumePolicy.progress(1500,1000)!!,0.01f)
        assertNull(MediaResumePolicy.progress(-1,1000))
    }
}
