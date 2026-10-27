package dev.luma.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ExecutorService

/** A transparent Home overlay: artwork is drawn by WallpaperView, with the original dock below. */
class CinematicContinueView(context:Context, private val prefs:Preferences,private val wallpaper:WallpaperView,
    private val images:TraktImageCache,
    private val worker:ExecutorService,private val entries:List<ContinueItem>,
    private val selected:(ContinueItem)->Unit,private val open:(ContinueItem)->Unit,
    private val dismiss:()->Unit,private val musicMode:Boolean=false):FrameLayout(context){
    private val u=minOf(resources.displayMetrics.widthPixels/1920f,resources.displayMetrics.heightPixels/1080f)
    private fun px(value:Int)=(value*u).toInt()
    private val provider=TextView(context).apply{setTextColor(0xBFFFFFFF.toInt());textSize=14f;letterSpacing=.14f;maxLines=1}
    private val title=TextView(context).apply{setTextColor(Color.WHITE);textSize=44f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val detail=TextView(context).apply{setTextColor(0xCFFFFFFF.toInt());textSize=20f;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val progress=View(context).apply{setBackgroundColor(0xD6FFFFFF.toInt())}
    private val progressTrack=View(context).apply{setBackgroundColor(0x38FFFFFF)}
    private val row=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false}
    private val scroller=HorizontalScrollView(context).apply{isHorizontalScrollBarEnabled=false;clipChildren=false;clipToPadding=false;addView(row)}
    private val cards=mutableListOf<FrameLayout>()
    private val musicButtons=mutableListOf<GlassButton>()
    private var musicSkipButton:GlassButton?=null
    private var lastMusicSkipAt=0L
    private var musicIndex=0
    private var musicItem=entries.first()
    private var musicTitle:TextView?=null
    private var musicArtist:TextView?=null
    private var musicCover:ImageView?=null
    private var musicArtRequest=0
    private var musicArtStamp=0L
    private var musicAmbient:View?=null
    private var musicAmbientColor=Color.TRANSPARENT
    private var musicProgressTrack:View?=null
    private var musicProgressFill:View?=null
    private val musicTick=object:Runnable{
        override fun run(){
            if(!isAttachedToWindow || !musicMode)return
            val item=musicItem
            val state=MediaResumeSource.musicPlayback(context,item)
            updateMusicPrimary(state)
            musicSkipButton?.let{button->
                val available=state?.canSkip==true
                button.isEnabled=available;button.alpha=if(available)1f else .45f
                if(!available && button.hasFocus()){
                    musicIndex=0;musicButtons.firstOrNull()?.requestFocus()
                }
            }
            val amount=state?.progress?:item.progress
            musicProgressTrack?.visibility=if(amount==null)GONE else VISIBLE
            musicProgressFill?.visibility=if(amount==null)GONE else VISIBLE
            if(amount!=null)musicProgressFill?.layoutParams=LayoutParams(
                (px(620)*amount.coerceIn(0f,100f)/100f).toInt().coerceAtLeast(px(2)),px(3)).apply{
                leftMargin=px(510);topMargin=px(440)}
            postDelayed(this,if(state?.playing==true)1000 else 2500)
        }
    }
    private var index=0
    init{
        isFocusable=false;clipChildren=false;clipToPadding=false
        addView(View(context).apply{background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            if(musicMode)intArrayOf(0xB5091720.toInt(),0x820D1D24.toInt(),0x2407111D)
            else intArrayOf(0x7107111D,0x2807111D,0x0007111D))},LayoutParams(-1,-1))
        if(musicMode)buildMusic(context) else {
        addView(provider,LayoutParams(px(700),px(30)).apply{leftMargin=px(102);topMargin=px(251)})
        addView(title,LayoutParams(px(1280),px(75)).apply{leftMargin=px(100);topMargin=px(289)})
        addView(detail,LayoutParams(px(1200),px(40)).apply{leftMargin=px(102);topMargin=px(374)})
        addView(progressTrack,LayoutParams(px(280),px(2)).apply{leftMargin=px(102);topMargin=px(439)})
        addView(progress,LayoutParams(px(0),px(2)).apply{leftMargin=px(102);topMargin=px(439)})
        addView(scroller,LayoutParams(-1,px(145)).apply{leftMargin=px(96);rightMargin=px(96);topMargin=px(514)})
        entries.forEachIndexed { position,item ->
            val surface=GradientDrawable().apply{setColor(Color.TRANSPARENT);cornerRadius=px(18).toFloat()}
            val card=FrameLayout(context).apply{
                isFocusable=true;isClickable=true;clipChildren=false
                contentDescription="${item.title} · ${item.sourceLabel}"
                background=GradientDrawable().apply{setColor(0xAA142134.toInt());cornerRadius=px(18).toFloat()}
                foreground=surface
                setOnFocusChangeListener{_,focused->
                    surface.setStroke(if(focused)px(2).coerceAtLeast(1) else 0,if(focused)0xCDE9F5FF.toInt() else Color.TRANSPARENT)
                    animate().cancel();animate().scaleX(if(focused)1.055f else 1f).scaleY(if(focused)1.055f else 1f)
                        .setDuration(if(prefs.motion==0)0 else 165).start()
                    elevation=if(focused)px(20).toFloat() else 0f
                    if(focused){index=position;render();selected(item);scroller.smoothScrollTo((position*px(234)-px(100)).coerceAtLeast(0),0)}
                }
                setOnClickListener{open(item)}
                setOnKeyListener{_,key,event->if(event.action!=KeyEvent.ACTION_DOWN)false else when(key){
                    KeyEvent.KEYCODE_DPAD_LEFT->{cards.getOrNull((position-1).coerceAtLeast(0))?.requestFocus();true}
                    KeyEvent.KEYCODE_DPAD_RIGHT->{cards.getOrNull((position+1).coerceAtMost(entries.lastIndex))?.requestFocus();true}
                    KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_BACK->{dismiss();true}
                    else->false
                }}
            }
            val image=ImageView(context).apply{scaleType=ImageView.ScaleType.CENTER_CROP;clipToOutline=true;
                background=GradientDrawable().apply{setColor(0x55405C7B);cornerRadius=px(16).toFloat()}}
            card.addView(image,LayoutParams(-1,-1))
            if(item.imageUrl.isBlank() && item.tileImageUrl.isBlank())card.addView(TextView(context).apply{
                text=item.title;setTextColor(Color.WHITE);textSize=16f;gravity=Gravity.CENTER;maxLines=3
                ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(px(10),px(8),px(10),px(8))
            },LayoutParams(-1,-1))
            row.addView(card,LinearLayout.LayoutParams(px(214),px(124)).apply{rightMargin=px(20);topMargin=px(8)})
            cards.add(card)
            val tileImage=item.tileImageUrl.ifBlank{item.imageUrl}
            if(tileImage.isNotBlank())post{worker.execute{val bitmap=runCatching{
                if(item.sourcePackage=="com.stremio.one" && item.tileImageUrl.isNotBlank())
                    StremioTitleCard.load(images,item.tileImageUrl,item.openUri)
                else images.load(tileImage,480)
            }.getOrNull();
                image.post{if(isAttachedToWindow && bitmap!=null)image.setImageBitmap(bitmap)}}}
        }
        render()
        }
        alpha=0f;translationY=px(8).toFloat()
        animate().alpha(1f).translationY(0f).setInterpolator(DecelerateInterpolator(1.5f))
            .setDuration(if(prefs.motion==0)0 else 300).start()
    }
    fun focusFirst(){
        if(musicMode){musicButtons.firstOrNull()?.requestFocus();removeCallbacks(musicTick);post(musicTick)}
        else {cards.firstOrNull()?.requestFocus();render()}
        selected(if(musicMode)musicItem else entries[index])
    }
    fun step(delta:Int){
        if(musicMode){
            var next=(musicIndex+delta).coerceIn(0,musicButtons.lastIndex)
            while(next in musicButtons.indices && !musicButtons[next].isEnabled &&
                next+delta in musicButtons.indices)next+=delta
            if(musicButtons.getOrNull(next)?.isEnabled==true){musicIndex=next;musicButtons[next].requestFocus()}
            return
        }
        val next=(index+delta).coerceIn(0,entries.lastIndex)
        if(next!=index){index=next;cards[index].requestFocus();render();selected(entries[index]);
            scroller.smoothScrollTo((index*px(234)-px(100)).coerceAtLeast(0),0)}
    }
    fun openCurrent(){if(musicMode)musicButtons.getOrNull(musicIndex)?.performClick() else open(entries[index])}
    fun currentItem()=if(musicMode)musicItem else entries[index]
    fun updateMusic(items:List<ContinueItem>){
        if(!musicMode)return
        val latest=items.filter{it.sourcePackage==musicItem.sourcePackage && it.origin=="Local media session"}
            .maxByOrNull{it.pausedAt.toLongOrNull()?:0L}?:return
        val previous=musicItem
        val newArtStamp=musicArtworkStamp(latest)
        if(latest.id==previous.id && latest.title==previous.title && latest.subtitle==previous.subtitle &&
            latest.imageUrl==previous.imageUrl && latest.tileImageUrl==previous.tileImageUrl &&
            newArtStamp==musicArtStamp)return
        musicItem=latest
        musicTitle?.text=latest.title
        musicArtist?.text=latest.subtitle.substringAfter('·',"").trim().ifBlank{"Spotify"}
        if(latest.id!=previous.id || latest.imageUrl!=previous.imageUrl || latest.tileImageUrl!=previous.tileImageUrl ||
            newArtStamp!=musicArtStamp)loadMusicCover(latest,latest.id!=previous.id)
        selected(latest)
        removeCallbacks(musicTick);post(musicTick)
    }
    private fun loadMusicCover(item:ContinueItem,newTrack:Boolean=false){
        val image=musicCover?:return
        val request=++musicArtRequest
        // Keep the old cover in place until the new bitmap is ready. Playback-state
        // callbacks may also rewrite the same image file without changing the song.
        val art=item.tileImageUrl.ifBlank{item.imageUrl}
        musicArtStamp=musicArtworkStamp(item)
        if(art.isBlank()){
            if(newTrack){image.setImageDrawable(null);showMusicAmbient(null)}
            return
        }
        post{worker.execute{
            val bitmap=runCatching{images.load(art,640)}.getOrNull()
            val tint=bitmap?.let(::albumAmbientColor)
            image.post{if(isAttachedToWindow && request==musicArtRequest){
                if(bitmap!=null){
                    val old=image.drawable
                    if(newTrack && old!=null && prefs.motion!=0){
                        val fade=TransitionDrawable(arrayOf(old,BitmapDrawable(resources,bitmap))).apply{
                            isCrossFadeEnabled=true
                        }
                        image.setImageDrawable(fade);fade.startTransition(240)
                        image.postDelayed({if(isAttachedToWindow && request==musicArtRequest)
                            image.setImageBitmap(bitmap)},260)
                    }else image.setImageBitmap(bitmap)
                    showMusicAmbient(tint)
                }else if(newTrack){image.setImageDrawable(null);showMusicAmbient(null)}
            }}
        }}
    }
    private fun showMusicAmbient(color:Int?){
        val nextColor=color?:Color.TRANSPARENT
        if(nextColor==musicAmbientColor)return
        musicAmbientColor=nextColor
        val previous=musicAmbient
        if(color==null){
            musicAmbient=null
            previous?.animate()?.cancel()
            previous?.animate()?.alpha(0f)?.setDuration(if(prefs.motion==0)0 else 300)
                ?.withEndAction{removeView(previous)}?.start()
            return
        }
        val red=Color.red(color);val green=Color.green(color);val blue=Color.blue(color)
        val layer=View(context).apply{
            background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(
                Color.argb(214,red,green,blue),Color.argb(140,red,green,blue),
                Color.argb(52,red,green,blue),Color.TRANSPARENT))
            alpha=0f
        }
        addView(layer,1,LayoutParams(-1,-1));musicAmbient=layer
        layer.animate().alpha(1f).setDuration(if(prefs.motion==0)0 else 330).start()
        previous?.animate()?.cancel()
        previous?.animate()?.alpha(0f)?.setDuration(if(prefs.motion==0)0 else 330)
            ?.withEndAction{removeView(previous)}?.start()
    }
    private fun albumAmbientColor(bitmap:Bitmap):Int{
        // Sample only 256 pixels off the UI thread. Prefer the cover's dominant colour
        // over a bright highlight, then darken it for legible TV text and glass controls.
        val sample=Bitmap.createScaledBitmap(bitmap,16,16,true)
        val pixels=IntArray(256);sample.getPixels(pixels,0,16,0,0,16,16)
        if(sample!==bitmap)sample.recycle()
        val scores=FloatArray(12);val reds=FloatArray(12);val greens=FloatArray(12);val blues=FloatArray(12)
        val hsv=FloatArray(3)
        for(pixel in pixels){
            Color.colorToHSV(pixel,hsv)
            if(hsv[1]<.18f || hsv[2]<.12f || hsv[2]>.94f)continue
            val bucket=(hsv[0]/30f).toInt().coerceIn(0,11)
            val weight=.35f+hsv[1]*.65f
            scores[bucket]+=weight;reds[bucket]+=Color.red(pixel)*weight
            greens[bucket]+=Color.green(pixel)*weight;blues[bucket]+=Color.blue(pixel)*weight
        }
        val bucket=scores.indices.maxByOrNull{scores[it]}?:0
        val picked=if(scores[bucket]>0f)Color.rgb(
            (reds[bucket]/scores[bucket]).toInt().coerceIn(0,255),
            (greens[bucket]/scores[bucket]).toInt().coerceIn(0,255),
            (blues[bucket]/scores[bucket]).toInt().coerceIn(0,255))
            else pixels.firstOrNull{Color.red(it)+Color.green(it)+Color.blue(it)>90}?:0xFF24344A.toInt()
        Color.colorToHSV(picked,hsv)
        hsv[1]=hsv[1].coerceIn(.26f,.68f);hsv[2]=hsv[2].coerceIn(.20f,.42f)
        return Color.HSVToColor(hsv)
    }
    private fun musicArtworkStamp(item:ContinueItem):Long{
        val art=item.tileImageUrl.ifBlank{item.imageUrl}
        if(!art.startsWith("file:"))return 0L
        return runCatching{java.io.File(java.net.URI(art)).lastModified()}.getOrDefault(0L)
    }
    private fun updateMusicPrimary(state:MediaResumeSource.MusicPlayback?){
        musicButtons.firstOrNull()?.apply{
            val controlsPlayback=state?.canToggle==true
            text=if(controlsPlayback)"" else "Open"
            foreground=if(controlsPlayback)MusicControlGlyph(
                if(state!!.playing)MusicControlGlyph.Kind.PAUSE else MusicControlGlyph.Kind.PLAY)else null
            textSize=19f
            contentDescription=when{
                !controlsPlayback->"Open Spotify to resume"
                state!!.playing->"Pause Spotify"
                else->"Play Spotify"
            }
            tag=if(controlsPlayback)"control" else "open"
        }
    }
    private fun buildMusic(context:Context){
        val item=musicItem
        fun label(value:String,size:Float,color:Int,medium:Boolean=false)=TextView(context).apply{
            text=value;textSize=size;setTextColor(color);maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.END;includeFontPadding=false
            if(medium)typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        }
        addView(label("SPOTIFY  ·  CONTINUE LISTENING",15f,0xCBFFFFFF.toInt()),
            LayoutParams(px(1000),px(32)).apply{leftMargin=px(510);topMargin=px(249)})
        val cover=FrameLayout(context).apply{
            background=GlassDrawable(this,wallpaper,prefs,px(29).toFloat(),true)
            setPadding(px(5),px(5),px(5),px(5))
        }
        val image=ImageView(context).apply{
            scaleType=ImageView.ScaleType.CENTER_CROP
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF173528.toInt(),0xFF111F32.toInt())).apply{cornerRadius=px(22).toFloat()}
            clipToOutline=true
        }
        musicCover=image
        cover.addView(image,LayoutParams(-1,-1))
        addView(cover,LayoutParams(px(310),px(310)).apply{leftMargin=px(145);topMargin=px(247)})
        loadMusicCover(item)
        val song=label(item.title,38f,Color.WHITE,true)
        musicTitle=song
        addView(song,
            LayoutParams(px(1110),px(75)).apply{leftMargin=px(508);topMargin=px(302)})
        val artist=item.subtitle.substringAfter('·',"").trim().ifBlank{"Spotify"}
        val artistLabel=label(artist,23f,0xD8FFFFFF.toInt())
        musicArtist=artistLabel
        addView(artistLabel,
            LayoutParams(px(1050),px(45)).apply{leftMargin=px(511);topMargin=px(377)})
        run{
            val track=View(context).apply{background=GradientDrawable().apply{
                setColor(0x4DFFFFFF);cornerRadius=px(2).toFloat()}}
            val fill=View(context).apply{background=GradientDrawable().apply{
                setColor(0xE8FFFFFF.toInt());cornerRadius=px(2).toFloat()}}
            musicProgressTrack=track;musicProgressFill=fill
            addView(track,LayoutParams(px(620),px(3)).apply{leftMargin=px(510);topMargin=px(440)})
            addView(fill,LayoutParams(px(2),px(3)).apply{
                leftMargin=px(510);topMargin=px(440)})
        }
        val playback=MediaResumeSource.musicPlayback(context,item)
        fun addControl(value:String,icon:Boolean=false,action:()->Unit):GlassButton{
            val position=musicButtons.size
            val button=GlassButton(context,wallpaper,prefs,value).apply{
                gravity=Gravity.CENTER;setPadding(0,0,0,0);maxLines=1
                textSize=if(icon)29f else 19f
                setOnClickListener{action()}
            }
            musicButtons.add(button)
            addView(button,LayoutParams(px(if(icon)108 else 170),px(65)).apply{
                leftMargin=px(510+position*126);topMargin=px(480)})
            return button
        }
        addControl("",true){
            val current=musicItem
            if(MediaResumeSource.musicPlayback(context,current)?.canToggle==true){
                if(MediaResumeSource.controlMusic(context,current,false)){
                    removeCallbacks(musicTick);postDelayed(musicTick,250)
                }
                else updateMusicPrimary(null)
            }else if(musicButtons.firstOrNull()?.tag=="open")open(current)
            else updateMusicPrimary(null)
        }
        updateMusicPrimary(playback)
        if(playback?.canSkip==true)musicSkipButton=addControl("",true){
            val now=android.os.SystemClock.elapsedRealtime()
            if(now-lastMusicSkipAt>=650 && MediaResumeSource.controlMusic(context,musicItem,true)){
                lastMusicSkipAt=now
                removeCallbacks(musicTick);postDelayed(musicTick,250)
            }
        }.apply{foreground=MusicControlGlyph(MusicControlGlyph.Kind.NEXT)
            contentDescription="Next Spotify track"}
        if(playback!=null)addControl("Open"){open(musicItem)}.apply{contentDescription="Open Spotify"}
    }
    override fun onDetachedFromWindow(){removeCallbacks(musicTick);super.onDetachedFromWindow()}
    private fun render(){
        val item=entries[index];title.text=item.title
        provider.text=item.sourceLabel.uppercase()+if(item.origin=="Added by you")"  ·  ADDED BY YOU" else ""
        detail.text=item.subtitle
        detail.visibility=if(item.subtitle.isBlank())GONE else VISIBLE
        val show=item.progress!=null
        progressTrack.visibility=if(show)VISIBLE else GONE;progress.visibility=if(show)VISIBLE else GONE
        if(show)progress.layoutParams=LayoutParams((px(280)*(item.progress!!/100f)).toInt().coerceAtLeast(2),px(2)).apply{leftMargin=px(102);topMargin=px(439)}
    }
    fun leave(done:()->Unit){
        if(musicMode)removeCallbacks(musicTick)
        animate().cancel()
        animate().alpha(0f).translationY(px(8).toFloat())
            .setInterpolator(DecelerateInterpolator(1.5f))
            .setDuration(if(prefs.motion==0)0 else 300).withEndAction(done).start()
    }
    fun returnToVisible(){
        if(musicMode){removeCallbacks(musicTick);post(musicTick)}
        animate().cancel()
        animate().alpha(1f).translationY(0f)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .setDuration(if(prefs.motion==0)0 else 300).start()
    }
}
