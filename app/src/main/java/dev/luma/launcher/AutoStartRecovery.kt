package dev.luma.launcher

/** Bounded readiness retries: Android may accept a start before the service is actually ready. */
internal class AutoStartRecovery(
    private val enabled:()->Boolean,
    private val ready:()->Boolean,
    private val start:()->Unit,
    private val schedule:(Long,()->Unit)->Unit,
    private val failed:()->Unit
) {
    private var generation=0
    fun cancel(){generation++}
    fun resume(){
        val request=++generation
        fun attempt(index:Int){
            if(request!=generation || !enabled() || ready())return
            if(index==DELAYS.size){failed();return}
            runCatching{start()}
            schedule(DELAYS[index]){attempt(index+1)}
        }
        attempt(0)
    }
    companion object {private val DELAYS=longArrayOf(2000,5000,15000,30000,60000)}
}
