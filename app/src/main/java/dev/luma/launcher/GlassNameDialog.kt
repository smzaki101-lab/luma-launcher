package dev.luma.launcher

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.util.concurrent.Executors

/** Shared QWERTY editor with an optional, dialog-scoped phone typing session. */
object GlassNameDialog {
    fun show(activity:Activity,wallpaper:WallpaperView,prefs:Preferences,title:String,value:String,limit:Int,confirmLabel:String="Save",saved:(String)->Unit):Dialog{
        fun dp(value:Int)=(value*activity.resources.displayMetrics.density).toInt()
        val main=Handler(Looper.getMainLooper())
        val worker=Executors.newSingleThreadExecutor()
        var session:PhoneTextSession?=null
        var generation=0
        var disposed=false
        var cleanup:()->Unit={}
        val dialog=object:Dialog(activity){override fun dismiss(){cleanup();super.dismiss()}}
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(14));background=GlassDrawable(this,wallpaper,prefs,dp(28).toFloat(),true)}
        fun label(value:String,size:Float=DesignTokens.secondarySp)=TextView(activity).apply{text=value;textSize=size;setTextColor(0xFFD0DEEF.toInt())}
        card.addView(label(title,20f))
        card.addView(label("Type with your remote, or scan to use your phone.").apply{setPadding(0,dp(5),0,0)})
        val body=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
        val editor=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        val field=EditText(activity).apply{
            id=View.generateViewId();setSingleLine();setTextColor(Color.WHITE);textSize=DesignTokens.rowSp
            filters=arrayOf(android.text.InputFilter.LengthFilter(limit));setText(value);setSelection(0,length())
            setPadding(dp(14),dp(8),dp(14),dp(8));showSoftInputOnFocus=false
            background=GlassDrawable(this,wallpaper,prefs,dp(12).toFloat());contentDescription=title
        }
        editor.addView(field,LinearLayout.LayoutParams(-1,dp(44)))
        val keyboard=QwertyKeyboardView(activity,field,36)
        editor.addView(keyboard,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        val buttons=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
        val save=GlassButton(activity,wallpaper,prefs,confirmLabel).apply{id=View.generateViewId();setOnClickListener{
            val result=field.text.toString().replace('\n',' ').replace('\r',' ').trim()
            if(result.isNotEmpty()){dialog.dismiss();saved(result)}else field.error="Enter some text"
        }}
        val cancel=GlassButton(activity,wallpaper,prefs,"Cancel").apply{id=View.generateViewId();setOnClickListener{dialog.dismiss()}}
        buttons.addView(save,LinearLayout.LayoutParams(0,dp(40),1f));buttons.addView(cancel,LinearLayout.LayoutParams(0,dp(40),1f).apply{leftMargin=dp(10)})
        editor.addView(buttons,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        body.addView(editor,LinearLayout.LayoutParams(0,-2,1f))
        val phone=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(18),0,0,0)}
        phone.addView(label("Type on your phone",DesignTokens.rowSp))
        val qr=ImageView(activity).apply{contentDescription="Scan to type on your phone";setBackgroundColor(Color.WHITE);setPadding(dp(6),dp(6),dp(6),dp(6));scaleType=ImageView.ScaleType.FIT_CENTER;visibility=View.INVISIBLE}
        phone.addView(qr,LinearLayout.LayoutParams(dp(160),dp(160)).apply{topMargin=dp(12)})
        val status=label("Preparing QR code…").apply{gravity=Gravity.CENTER;setPadding(0,dp(10),0,dp(8));minHeight=dp(62)}
        phone.addView(status,LinearLayout.LayoutParams(-1,-2))
        val refresh=GlassButton(activity,wallpaper,prefs,"New QR code").apply{id=View.generateViewId()}
        phone.addView(refresh,LinearLayout.LayoutParams(-1,dp(44)))
        body.addView(phone,LinearLayout.LayoutParams(dp(212),-2))
        card.addView(body,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        dialog.setContentView(card)
        keyboard.connectFocus(save,cancel,refresh)
        field.nextFocusRightId=refresh.id;refresh.nextFocusUpId=field.id;refresh.nextFocusDownId=save.id
        save.nextFocusRightId=cancel.id;cancel.nextFocusLeftId=save.id

        var phoneHidden=false
        fun phoneTyping(active:Boolean){
            if(disposed || !dialog.isShowing || active==phoneHidden)return
            phoneHidden=active
            card.visibility=if(active)View.INVISIBLE else View.VISIBLE
            dialog.window?.apply{
                if(active){
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    setLayout(1,1)
                }else{
                    clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    setLayout((activity.resources.displayMetrics.widthPixels*.84f).toInt(),-2)
                }
            }
        }
        fun stopSession(message:String){
            if(!disposed)phoneTyping(false)
            generation++;session?.let{PhoneRemoteControl.detach(it)};session=null;qr.setImageDrawable(null);qr.tag=null;qr.visibility=View.INVISIBLE
            status.text=message
        }
        fun startSession(){
            stopSession("Preparing QR code…")
            val request=generation
            val initial=field.text.toString()
            worker.execute{
                var created:PhoneTextSession?=null
                val result=runCatching{
                    val manager=activity.getSystemService(ConnectivityManager::class.java)
                    val network=manager.activeNetwork?:error("Connect the projector to Wi-Fi to use phone typing.")
                    val address=manager.getLinkProperties(network)?.linkAddresses?.map{it.address}?.filterIsInstance<Inet4Address>()
                        ?.firstOrNull{!it.isLoopbackAddress && !it.isLinkLocalAddress}?:error("Connect the projector to Wi-Fi to use phone typing.")
                    val html=activity.assets.open("phone-keyboard.html").bufferedReader().use{it.readText()}
                    val next=PhoneTextSession(address,html,title,limit,initial,onText={incoming->main.post{
                        if(!disposed && request==generation && dialog.isShowing && session?.isOpen==true){
                            phoneTyping(true)
                            field.setText(incoming);field.setSelection(field.length());status.setText(R.string.phone_keyboard_connected)
                        }
                    }},onExpired={main.post{if(!disposed && request==generation)stopSession("Code expired\nSelect New QR code.")}},onSubmit={incoming->main.post{
                        if(!disposed && request==generation && dialog.isShowing && session?.isOpen==true){
                            field.setText(incoming);field.setSelection(field.length());save.performClick()
                        }
                    }},onRemote=PhoneRemoteControl.callback(activity),icon=activity.assets.open("luma-remote-icon.png").use{it.readBytes()},onInputReady={FastPhoneInput.ready()},onFocusNavigation={FastPhoneInput.usesNativeFocus()},onMagneticTarget={PhoneMouseCursor.magneticTarget()},onApps={PhoneRemoteControl.apps(activity)},onLaunch={PhoneRemoteControl.launch(activity,it)},onConnected={main.post{if(!disposed && request==generation)phoneTyping(true)}})
                    PhoneRemoteControl.register(next)
                    created=next
                    val phoneUrl=PhoneRemoteService.url(activity)?:next.url
                val matrix=QRCodeWriter().encode(phoneUrl,BarcodeFormat.QR_CODE,360,360,mapOf(EncodeHintType.MARGIN to 3))
                    val pixels=IntArray(matrix.width*matrix.height){index->if(matrix[index%matrix.width,index/matrix.width])Color.BLACK else Color.WHITE}
                    next to Bitmap.createBitmap(pixels,matrix.width,matrix.height,Bitmap.Config.ARGB_8888)
                }
                main.post{
                    if(disposed || request!=generation || !dialog.isShowing){created?.close();return@post}
                    result.onSuccess{(next,bitmap)->session=next;next.updateText(field.text.toString());qr.setImageBitmap(bitmap);qr.tag=PhoneRemoteService.url(activity)?:next.url;qr.visibility=View.VISIBLE;status.setText(R.string.phone_keyboard_ready)}
                        .onFailure{created?.close();status.text=if(it is IllegalStateException)it.message else activity.getString(R.string.phone_keyboard_unavailable)}
                }
            }
        }
        refresh.setOnClickListener{startSession()}
        field.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){session?.updateText(s?.toString()?:"")}
            override fun afterTextChanged(s:Editable?){}
        })
        val lifecycle=object:Application.ActivityLifecycleCallbacks{
            override fun onActivityCreated(a:Activity,state:Bundle?){}
            override fun onActivityStarted(a:Activity){}
            override fun onActivityResumed(a:Activity){if(a===activity && dialog.isShowing && !disposed){
                if(session?.isOpen==true)session?.resumeText(field.text.toString()) else startSession()
            }}
            override fun onActivityPaused(a:Activity){}
            override fun onActivityStopped(a:Activity){if(a===activity && !disposed){
                if(session?.isOpen==true && session?.hasRemote==true)session?.detachText()
                else stopSession("Phone typing paused\nSelect New QR code.")
            }}
            override fun onActivitySaveInstanceState(a:Activity,state:Bundle){}
            override fun onActivityDestroyed(a:Activity){if(a===activity)dialog.dismiss()}
        }
        cleanup={if(!disposed){disposed=true;stopSession("");activity.application.unregisterActivityLifecycleCallbacks(lifecycle);worker.shutdownNow()}}
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show();dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels*.84f).toInt(),-2);dialog.window?.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        dialog.window?.attributes=dialog.window!!.attributes.apply{y=dp(18);dimAmount=.16f}
        keyboard.firstKey.requestFocus()
        startSession()
        return dialog
    }
}
