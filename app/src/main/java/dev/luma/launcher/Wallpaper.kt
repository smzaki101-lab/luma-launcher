package dev.luma.launcher

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.animation.ValueAnimator
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class WallpaperRepository(private val context: Context) {
    private data class WallpaperAsset(val bitmap:Bitmap,val frost:Bitmap)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private val cache = object : android.util.LruCache<String, WallpaperAsset>(DeviceBudget.wallpaperCacheBytes(context)) {
        override fun sizeOf(key: String, value: WallpaperAsset) = value.bitmap.allocationByteCount+value.frost.allocationByteCount
    }
    fun load(uri: String, maxEdge: Int = 1920, done: (Bitmap?, Bitmap?) -> Unit) {
        val request = ++generation
        executor.execute {
            val key = "$uri:$maxEdge"
            val asset = cache.get(key) ?: runCatching {
                val bitmap=decode(uri,maxEdge)
                WallpaperAsset(bitmap,soften(bitmap))
            }.getOrNull()?.also { cache.put(key,it) }
            main.post { if (request == generation) done(asset?.bitmap, asset?.frost) }
        }
    }
    private fun decode(uri: String, maxEdge: Int): Bitmap {
        fun stream() = when (uri) {
            "" -> context.resources.openRawResource(R.raw.fjord)
            "builtin:blue_hour_coast" -> context.resources.openRawResource(R.raw.blue_hour_coast)
            "builtin:midnight_fjord" -> context.resources.openRawResource(R.raw.midnight_fjord)
            else -> context.contentResolver.openInputStream(Uri.parse(uri)) ?: error("Image unavailable")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = stream().use { BitmapFactory.decodeStream(it, null, options) } ?: error("Unsupported image")
        // Framework ExifInterface handles JPEG orientation; other formats use their decoded orientation.
        val orientation = if (android.os.Build.VERSION.SDK_INT >= 24 && uri.isNotBlank()) runCatching {
            stream().use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }
        }.getOrDefault(1) else 1
        val matrix = Matrix()
        when (orientation) { 2 -> matrix.setScale(-1f,1f); 3 -> matrix.setRotate(180f); 4 -> matrix.setScale(1f,-1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f,1f) }; 6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(270f); matrix.postScale(-1f,1f) }; 8 -> matrix.setRotate(270f) }
        return if (!matrix.isIdentity) Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true) else decoded
    }
    companion object {
    fun soften(source: Bitmap): Bitmap {
        val w = 96; val h = max(1, source.height * w / source.width)
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val input = IntArray(w*h); small.getPixels(input,0,w,0,0,w,h)
        val output = IntArray(input.size)
        // Small separable box filter, executed only after artwork changes.
        repeat(3) {
            for (y in 0 until h) for (x in 0 until w) {
                var r=0; var g=0; var b=0; var count=0
                for (dx in -2..2) { val c=input[y*w+(x+dx).coerceIn(0,w-1)]; r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c);count++ }
                output[y*w+x]=Color.rgb(r/count,g/count,b/count)
            }
            for (y in 0 until h) for (x in 0 until w) {
                var r=0;var g=0;var b=0;var count=0
                for (dy in -2..2) { val c=output[(y+dy).coerceIn(0,h-1)*w+x];r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c);count++ }
                input[y*w+x]=Color.rgb(r/count,g/count,b/count)
            }
        }
        return Bitmap.createBitmap(input,w,h,Bitmap.Config.ARGB_8888)
    }
    }
    fun trimMemory() { cache.evictAll() }
    fun close() { generation++; executor.shutdownNow(); cache.evictAll() }
}

class WallpaperView(context: Context) : View(context) {
    var bitmap: Bitmap? = null; private set
    var frost: Bitmap? = null; private set
    private var old: Bitmap? = null
    private var blend = 1f
    private var fade: ValueAnimator? = null
    var cinematicShade = true
    var darkness = .12f
        set(value){if(field!=value){field=value;invalidate()}}
    var zoom = 1f
    var drift = 0f
    var driftY = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination=RectF()
    private var shade: Shader?=null
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        shade=LinearGradient(0f,0f,0f,h.toFloat(),intArrayOf(0x05040B13,0x12040B13,0xB0040B13.toInt()),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
    }
    fun show(next: Bitmap, blurred: Bitmap, animated: Boolean = true, durationMs:Long=420) {
        fade?.cancel(); old = bitmap; bitmap = next; frost = blurred
        if (animated && old != null && old !== next) {
            fade = ValueAnimator.ofFloat(0f,1f).apply {
                duration=durationMs
                addUpdateListener { blend=it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { old=null; blend=1f; invalidate() }
                })
                start()
            }
        } else { blend=1f; old=null; invalidate() }
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(15,27,42))
        old?.takeIf { blend < 1 }?.let { drawImage(canvas,it,255) }
        bitmap?.let { drawImage(canvas,it,(blend*255).toInt()) }
        paint.alpha=255; paint.shader=shade
        if(cinematicShade) canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint); paint.shader=null
        paint.color=Color.argb((darkness*255).toInt().coerceIn(0,220),0,0,0); canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
    }
    private fun drawImage(c: Canvas,b: Bitmap,a: Int) {
        val scale=max(width.toFloat()/b.width,height.toFloat()/b.height)*zoom
        val w=b.width*scale;val h=b.height*scale
        paint.alpha=a; paint.shader=null
        val x=drift.coerceIn(-max(0f,(w-width)/2),max(0f,(w-width)/2))
        val y=driftY.coerceIn(-max(0f,(h-height)/2),max(0f,(h-height)/2))
        destination.set((width-w)/2+x,(height-h)/2+y,(width+w)/2+x,(height+h)/2+y);c.drawBitmap(b,null,destination,paint)
    }
    fun releaseTransition() {fade?.cancel();fade=null;old=null;blend=1f}
    override fun onDetachedFromWindow() { releaseTransition(); super.onDetachedFromWindow() }
}

/** One cached hardware layer for artwork fades; avoids redrawing two full-screen bitmaps per frame. */
class CinematicBackdropView(context:Context,private val art:Bitmap,private val dark:Float):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect=RectF()
    private var shade:Shader?=null
    init{setLayerType(LAYER_TYPE_HARDWARE,null)}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        super.onSizeChanged(w,h,oldw,oldh)
        shade=LinearGradient(0f,0f,0f,h.toFloat(),intArrayOf(0x05040B13,0x12040B13,0xB0040B13.toInt()),
            floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas:Canvas){
        val scale=max(width.toFloat()/art.width,height.toFloat()/art.height)
        val w=art.width*scale;val h=art.height*scale
        rect.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
        paint.color=Color.WHITE;paint.alpha=255;paint.shader=null;canvas.drawBitmap(art,null,rect,paint)
        paint.shader=shade;canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
        paint.shader=null;paint.color=Color.argb((dark*255).toInt().coerceIn(0,220),0,0,0)
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
    }
}
