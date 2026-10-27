package dev.luma.launcher

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*

data class PanelAction(val title: String, val action: () -> Unit)

class GlassPanel(context: Context, wallpaper: WallpaperView, prefs: Preferences,
    title: String, subtitle: String, actions: List<PanelAction>, compact: Boolean = false,
    networkAction:PanelAction?=null, private val controlOpenMs:Long=220,
    private val controlCloseMs:Long=190) : FrameLayout(context) {
    val buttons=mutableListOf<View>()
    private lateinit var card:LinearLayout
    private var clockLabel:TextView?=null
    private var networkButton:ControlActionView?=null
    private var dismissing=false
    private var afterClose:(()->Unit)?=null
    private val control=title=="Control Centre"
    private var controlAnimator:ValueAnimator?=null
    private var controlProgress=0f
    private val motion=Spring{value->
        val visible=value.coerceIn(0f,1f)
        card.alpha=visible;card.translationX=(1f-value)*24*resources.displayMetrics.density
        background?.alpha=(85*visible).toInt()
        if(value==0f && dismissing){card.setLayerType(View.LAYER_TYPE_NONE,null);afterClose?.invoke();afterClose=null}
        else if(value==1f)card.setLayerType(View.LAYER_TYPE_NONE,null)
    }
    private fun renderControl(value:Float){
        controlProgress=value.coerceIn(0f,1f)
        card.alpha=controlProgress
        card.translationX=(1f-controlProgress)*24.dp
        background?.alpha=(115*controlProgress).toInt()
    }
    private fun animateControl(target:Float,duration:Long){
        controlAnimator?.cancel()
        controlAnimator=ValueAnimator.ofFloat(controlProgress,target).apply{
            this.duration=duration;interpolator=DecelerateInterpolator(1.5f)
            addUpdateListener{renderControl(it.animatedValue as Float)}
            addListener(object:android.animation.AnimatorListenerAdapter(){
                override fun onAnimationEnd(animation:android.animation.Animator){
                    if(controlProgress==target){card.setLayerType(View.LAYER_TYPE_NONE,null)
                        if(dismissing && target==0f){afterClose?.invoke();afterClose=null}}
                }
            })
            start()
        }
    }
    fun enter(animated:Boolean){
        dismissing=false;card.setLayerType(View.LAYER_TYPE_HARDWARE,null)
        if(control){renderControl(if(animated)0f else 1f);if(animated)animateControl(1f,controlOpenMs) else card.setLayerType(View.LAYER_TYPE_NONE,null)}
        else {motion.snap(if(animated)0f else 1f);if(animated)motion.to(1f)}
    }
    fun leave(animated:Boolean,done:()->Unit){
        dismissing=true;afterClose=done;card.setLayerType(View.LAYER_TYPE_HARDWARE,null)
        if(control){if(animated)animateControl(0f,controlCloseMs) else {controlAnimator?.cancel();renderControl(0f);card.setLayerType(View.LAYER_TYPE_NONE,null);afterClose?.invoke();afterClose=null}}
        else {if(animated)motion.to(0f)else motion.snap(0f)}
    }
    override fun onDetachedFromWindow(){controlAnimator?.cancel();motion.stop();afterClose=null;super.onDetachedFromWindow()}
    init {
        setBackgroundColor(0x73030A13)
        card=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding((if(control)22 else 26).dp,(if(control)22 else 26).dp,(if(control)22 else 26).dp,(if(control)22 else 26).dp)
            background=GlassDrawable(this,wallpaper,prefs,32.dp.toFloat(),true,control)
        }
        val width=(resources.displayMetrics.widthPixels*(if(compact).32f else if(control).32f else .41f)).toInt().coerceAtLeast(340.dp)
        val safeX=maxOf(24.dp,resources.displayMetrics.widthPixels*prefs.number("safe",5)/100)
        val safeY=maxOf(20.dp,resources.displayMetrics.heightPixels*prefs.number("safe",5)/100)
        addView(card,LayoutParams(width.coerceAtMost(resources.displayMetrics.widthPixels-2*safeX),if(compact) minOf(resources.displayMetrics.heightPixels-2*safeY,(180+actions.size*(DesignTokens.rowHeightDp+DesignTokens.rowGapDp)).dp) else if(control)-2 else -1,Gravity.RIGHT or if(compact) Gravity.CENTER_VERTICAL else Gravity.TOP).apply{setMargins(safeX,safeY,safeX,safeY)})
        if(control)buildControlCentre(context,wallpaper,prefs,subtitle,actions,networkAction)
        else {
            card.addView(TextView(context).apply {text=title;setTextColor(Color.WHITE);textSize=DesignTokens.headingSp;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;includeFontPadding=false;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)},LinearLayout.LayoutParams(-1,-2))
            card.addView(TextView(context).apply {text=subtitle;setTextColor(0xBEFFFFFF.toInt());textSize=DesignTokens.secondarySp;maxLines=when(title){"Device diagnostics"->10;"Connect Trakt","Integration status"->8;else->3};ellipsize=android.text.TextUtils.TruncateAt.END;includeFontPadding=false;setPadding(0,10.dp,0,18.dp)},LinearLayout.LayoutParams(-1,-2))
            val scroll=ScrollView(context).apply{isVerticalScrollBarEnabled=false;clipToPadding=true;clipChildren=true}
            val items=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false;setPadding(4.dp,6.dp,4.dp,14.dp)}
            scroll.addView(items);card.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
            actions.forEach { a ->
                val button=GlassButton(context,wallpaper,prefs,a.title).apply {id=View.generateViewId();setOnClickListener{a.action()}}
                buttons.add(button);items.addView(button,LinearLayout.LayoutParams(-1,DesignTokens.rowHeightDp.dp).apply{bottomMargin=(DesignTokens.rowGapDp+4).dp})
            }
            buttons.forEachIndexed { i,b ->
                b.nextFocusUpId=buttons[(i-1).coerceAtLeast(0)].id
                b.nextFocusDownId=buttons[(i+1).coerceAtMost(buttons.lastIndex)].id
            }
        }
    }
    private fun buildControlCentre(context:Context,wallpaper:WallpaperView,prefs:Preferences,subtitle:String,actions:List<PanelAction>,networkAction:PanelAction?){
        val header=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        header.addView(TextView(context).apply{
            text="Control Centre";setTextColor(Color.WHITE);textSize=25f;letterSpacing=-.015f
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);includeFontPadding=false
        },LinearLayout.LayoutParams(0,39.dp,1f))
        val panelClock=TextView(context).apply{
            setTextColor(Color.WHITE);textSize=17f;includeFontPadding=false;gravity=Gravity.CENTER
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setPadding(13.dp,0,13.dp,0);setSingleLine(true);contentDescription="Control Centre time"
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0x35E3F1FF,0x139FBBDD)).apply{cornerRadius=15.dp.toFloat()}
        }
        clockLabel=panelClock
        header.addView(panelClock,LinearLayout.LayoutParams(-2,34.dp))
        card.addView(header,LinearLayout.LayoutParams(-1,42.dp))
        card.addView(TextView(context).apply{
            text=subtitle.substringBefore(" · ");setTextColor(0xB9EAF1FA.toInt());textSize=14f
            setSingleLine(true);ellipsize=android.text.TextUtils.TruncateAt.END;includeFontPadding=false
        },LinearLayout.LayoutParams(-1,29.dp))
        val music=MediaResumeSource.read(context)
            .firstOrNull{MediaResumePolicy.labels[it.sourcePackage]=="Spotify"}
            ?.takeIf{MediaResumeSource.musicPlayback(context,it)!=null}
            ?.let{NowPlayingControlView(context,it)}
        music?.let{
            card.addView(it,LinearLayout.LayoutParams(-1,81.dp).apply{bottomMargin=7.dp})
            val safeY=maxOf(20.dp,resources.displayMetrics.heightPixels*prefs.number("safe",5)/100)
            card.layoutParams.height=minOf(resources.displayMetrics.heightPixels-2*safeY,650.dp)
        }
        val scroll=ScrollView(context).apply{isVerticalScrollBarEnabled=false;clipToPadding=false;clipChildren=true;isFocusable=false}
        val items=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;clipChildren=false;setPadding(2.dp,5.dp,2.dp,12.dp)}
        scroll.addView(items);card.addView(scroll,if(music==null)LinearLayout.LayoutParams(-1,-2)
            else LinearLayout.LayoutParams(-1,0,1f))
        fun section(label:String){items.addView(TextView(context).apply{
            text=label;setTextColor(0xAFFFFFFF.toInt());textSize=12f;letterSpacing=.12f
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);includeFontPadding=false;gravity=Gravity.BOTTOM
        },LinearLayout.LayoutParams(-1,32.dp).apply{leftMargin=9.dp})}
        val heroRow=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;clipChildren=false;clipToPadding=false}
        items.addView(heroRow,LinearLayout.LayoutParams(-1,100.dp).apply{bottomMargin=14.dp})
        listOf("grid","switch").forEachIndexed { index,glyph ->
            actions.getOrNull(index)?.let{a->
                val button=ControlActionView(context,wallpaper,prefs,a.title,glyph,true).apply{id=View.generateViewId();setOnClickListener{a.action()}}
                buttons.add(button);heroRow.addView(button,LinearLayout.LayoutParams(0,-1,1f).apply{if(index>0)leftMargin=10.dp})
            }
        }
        section("QUICK CONTROLS")
        listOf("search","audio","wallpaper","sleep").forEachIndexed { index,glyph ->
            actions.getOrNull(index+2)?.let{a->
                val button=ControlActionView(context,wallpaper,prefs,a.title,glyph).apply{id=View.generateViewId();setOnClickListener{a.action()}}
                buttons.add(button);items.addView(button,LinearLayout.LayoutParams(-1,51.dp).apply{bottomMargin=4.dp})
            }
        }
        section("MORE")
        actions.getOrNull(6)?.let{a->
            val button=ControlActionView(context,wallpaper,prefs,a.title,"more",subtle=true).apply{id=View.generateViewId();setOnClickListener{a.action()}}
            buttons.add(button);items.addView(button,LinearLayout.LayoutParams(-1,45.dp))
        }
        networkButton?.let{buttons.add(it)}
        buttons.forEachIndexed{i,b->
            b.nextFocusUpId=if(i<=1)b.id else if(i==2)buttons[0].id else buttons[i-1].id
            b.nextFocusDownId=if(i<=1 && buttons.size>2)buttons[2].id else buttons[(i+1).coerceAtMost(buttons.lastIndex)].id
            b.nextFocusLeftId=if(i==1)buttons[0].id else b.id
            b.nextFocusRightId=if(i==0 && buttons.size>1)buttons[1].id else b.id
        }
        music?.let{row->
            row.play.id=View.generateViewId();row.next.id=View.generateViewId()
            row.play.nextFocusUpId=row.play.id;row.next.nextFocusUpId=row.next.id
            row.play.nextFocusDownId=buttons[0].id;row.next.nextFocusDownId=buttons[1].id
            row.play.nextFocusLeftId=row.play.id;row.play.nextFocusRightId=row.next.id
            row.next.nextFocusLeftId=row.play.id;row.next.nextFocusRightId=row.next.id
            buttons[0].nextFocusUpId=row.play.id;buttons[1].nextFocusUpId=row.next.id
        }
    }
    fun focusAt(index: Int=0) { buttons.getOrNull(index.coerceIn(0,(buttons.size-1).coerceAtLeast(0)))?.requestFocus() }
    fun setClockText(value:String){clockLabel?.text=value}
    fun setNetworkStatus(value:String){networkButton?.setTitle(value)}
    private val Int.dp get()=(this*resources.displayMetrics.density).toInt()
}
