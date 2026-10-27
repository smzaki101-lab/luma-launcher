package dev.luma.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Small vector playback symbols, avoiding device-dependent emoji fonts. */
internal class MusicControlGlyph(private val kind:Kind):Drawable(){
    enum class Kind{PLAY,PAUSE,NEXT}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;style=Paint.Style.FILL}
    override fun draw(canvas:Canvas){
        val area=bounds
        val scale=minOf(area.width()/108f,area.height()/65f)
        val save=canvas.save()
        canvas.translate(area.exactCenterX(),area.exactCenterY())
        canvas.scale(scale,scale)
        when(kind){
            Kind.PLAY->triangle(canvas,-10f,-14f,13f,0f)
            Kind.PAUSE->{
                canvas.drawRoundRect(RectF(-11f,-14f,-5f,14f),1.5f,1.5f,paint)
                canvas.drawRoundRect(RectF(5f,-14f,11f,14f),1.5f,1.5f,paint)
            }
            Kind.NEXT->{
                triangle(canvas,-17f,-14f,-3f,0f)
                triangle(canvas,-2f,-14f,12f,0f)
                canvas.drawRoundRect(RectF(14f,-14f,18f,14f),1f,1f,paint)
            }
        }
        canvas.restoreToCount(save)
    }
    private fun triangle(canvas:Canvas,left:Float,top:Float,right:Float,middle:Float){
        val path=Path().apply{moveTo(left,top);lineTo(right,middle);lineTo(left,-top);close()}
        canvas.drawPath(path,paint)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(colorFilter:ColorFilter?){paint.colorFilter=colorFilter;invalidateSelf()}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
