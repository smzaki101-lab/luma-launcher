package dev.luma.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** Compact, session-backed music controls for the Control Centre. */
internal class NowPlayingControlView(context:Context, initial:ContinueItem):LinearLayout(context){
    private var item=initial
    private var coverPath=""
    private var coverRequest=0
    private val cover=ImageView(context).apply{scaleType=ImageView.ScaleType.CENTER_CROP;clipToOutline=true
        background=GradientDrawable().apply{setColor(0x55526779);cornerRadius=12.dp.toFloat()}}
    private val title=TextView(context).apply{setTextColor(Color.WHITE);textSize=16f;maxLines=1
        ellipsize=android.text.TextUtils.TruncateAt.END;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);includeFontPadding=false}
    private val artist=TextView(context).apply{setTextColor(0xBFE9F2FF.toInt());textSize=13f;maxLines=1
        ellipsize=android.text.TextUtils.TruncateAt.END;includeFontPadding=false}
    val play=control("Play or pause",MusicControlGlyph.Kind.PLAY)
    val next=control("Next track",MusicControlGlyph.Kind.NEXT)
    private val tick=object:Runnable{
        override fun run(){
            if(!isAttachedToWindow)return
            MediaResumeSource.read(context).firstOrNull{MediaResumePolicy.labels[it.sourcePackage]=="Spotify"}
                ?.takeIf{MediaResumeSource.musicPlayback(context,it)!=null}?.let{fresh->
                    if(fresh.id!=item.id || fresh.imageUrl!=item.imageUrl){item=fresh;renderTrack()}
                }
            renderPlayback()
            postDelayed(this,1800)
        }
    }
    init{
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        setPadding(11.dp,7.dp,10.dp,7.dp)
        background=GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0x57DDEEFF,0x267D98B7,0x24465B75)).apply{
            cornerRadius=19.dp.toFloat();setStroke(1.dp,0x35FFFFFF)
        }
        addView(cover,LayoutParams(59.dp,59.dp).apply{rightMargin=12.dp})
        val labels=LinearLayout(context).apply{orientation=VERTICAL;gravity=Gravity.CENTER_VERTICAL}
        labels.addView(TextView(context).apply{text="NOW PLAYING";textSize=10f;letterSpacing=.13f
            setTextColor(0xAEEAF4FF.toInt());includeFontPadding=false},LayoutParams(-1,15.dp))
        labels.addView(title,LayoutParams(-1,23.dp))
        labels.addView(artist,LayoutParams(-1,19.dp))
        addView(labels,LayoutParams(0,-1,1f).apply{rightMargin=8.dp})
        addView(play,LayoutParams(38.dp,38.dp).apply{rightMargin=5.dp})
        addView(next,LayoutParams(38.dp,38.dp))
        play.setOnClickListener{
            val wasPlaying=MediaResumeSource.musicPlayback(context,item)?.playing==true
            if(MediaResumeSource.controlMusic(context,item,false)){
                play.foreground=MusicControlGlyph(if(wasPlaying)MusicControlGlyph.Kind.PLAY else MusicControlGlyph.Kind.PAUSE)
                play.contentDescription=(if(wasPlaying)"Play " else "Pause ")+item.title
                postDelayed({renderPlayback()},350)
            }
        }
        next.setOnClickListener{if(MediaResumeSource.controlMusic(context,item,true)){
            next.isEnabled=false;next.alpha=.5f;postDelayed({renderPlayback()},900)
        }}
        renderTrack();renderPlayback()
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();postDelayed(tick,1800)}
    override fun onDetachedFromWindow(){removeCallbacks(tick);coverRequest++;super.onDetachedFromWindow()}
    private fun renderTrack(){
        title.text=item.title
        artist.text=item.subtitle.substringAfter(" · ","").ifBlank{"Spotify"}
        contentDescription="Now playing ${item.title}, ${item.subtitle}"
        val path=runCatching{Uri.parse(item.imageUrl).takeIf{it.scheme=="file"}?.path}.getOrNull().orEmpty()
        if(path==coverPath)return
        coverPath=path;val request=++coverRequest
        if(path.isBlank()){cover.setImageDrawable(null);return}
        Thread({
            val bitmap=runCatching{
                val file=File(path).takeIf{it.isFile && it.canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator)}
                    ?:return@runCatching null
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeFile(file.path,bounds)
                val sample=(maxOf(bounds.outWidth,bounds.outHeight)/160).coerceAtLeast(1)
                BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})
            }.getOrNull()
            if(!post{if(request==coverRequest)cover.setImageBitmap(bitmap)else bitmap?.recycle()})bitmap?.recycle()
        },"Luma-now-playing-art").start()
    }
    private fun renderPlayback(){
        val state=MediaResumeSource.musicPlayback(context,item)
        play.foreground=MusicControlGlyph(if(state?.playing==true)MusicControlGlyph.Kind.PAUSE else MusicControlGlyph.Kind.PLAY)
        play.contentDescription=if(state?.playing==true)"Pause ${item.title}" else "Play ${item.title}"
        play.isEnabled=state?.canToggle==true;play.alpha=if(play.isEnabled)1f else .48f
        next.isEnabled=state?.canSkip==true;next.alpha=if(next.isEnabled)1f else .48f
    }
    private fun control(label:String,glyph:MusicControlGlyph.Kind)=FrameLayout(context).apply{
        isFocusable=true;isFocusableInTouchMode=true;isClickable=true;contentDescription=label
        foreground=MusicControlGlyph(glyph)
        fun surface(focused:Boolean)=GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if(focused)intArrayOf(0x80E8F5FF.toInt(),0x6582AACB)else intArrayOf(0x42D7E9FF,0x2480A5C6)).apply{
            cornerRadius=14.dp.toFloat();setStroke(1.dp,if(focused)0xBDEAF8FF.toInt() else 0x35FFFFFF)
        }
        background=surface(false)
        setOnFocusChangeListener{_,focused->background=surface(focused);animate().cancel()
            animate().scaleX(if(focused)1.06f else 1f).scaleY(if(focused)1.06f else 1f).setDuration(150).start()}
    }
    private val Int.dp get()=(this*resources.displayMetrics.density).toInt()
}
