package dev.luma.launcher

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.util.concurrent.Executors

/** The bundled Android IME: edits the app's real InputConnection without opening a launcher task. */
class LumaInputMethodService:InputMethodService(){
    override fun onCreate(){super.onCreate();instance=java.lang.ref.WeakReference(this)}
    private fun acceptOverlay(source:String,text:String):Boolean{
        if(currentInputEditorInfo?.packageName!=source || currentInputEditorInfo?.inputType==InputType.TYPE_NULL || secret)return false
        val connection=currentInputConnection?:return false
        val previous=snapshot()
        connection.beginBatchEdit()
        val accepted=try{
            connection.finishComposingText()
            if(previous!=null && !connection.setSelection(0,previous.length))return false
            connection.commitText(text,1)
        }finally{connection.endBatchEdit()}
        if(!accepted)return false
        if(!sendDefaultEditorAction(false))sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        requestHideSelf(0);return true
    }
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var generation=0
    private var editing=false
    private var phoneTyping=false
    private fun phoneTyping(active:Boolean){
        if(active && (!editing || secret))return
        phoneTyping=active
        if(::root.isInitialized){
            root.visibility=if(active)View.INVISIBLE else View.VISIBLE
            root.importantForAccessibility=if(active)View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            root.requestLayout()
        }
    }
    private var session:PhoneTextSession?=null
    private lateinit var root:FrameLayout
    private lateinit var card:LinearLayout
    private lateinit var preview:EditText
    private lateinit var keyboard:QwertyKeyboardView
    private lateinit var qr:ImageView
    private lateinit var status:TextView
    private lateinit var go:TextView
    private lateinit var hide:TextView
    private lateinit var wifiPaste:TextView
    private lateinit var wifiChange:TextView
    private lateinit var wifiForget:TextView
    private lateinit var phoneTitle:TextView
    private lateinit var phoneRefresh:TextView
    private var wifiDraft:String?=null
    private var wifiCursor=0
    private var wifiField=false
    private var secret=false
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Float=13f)=TextView(this).apply{text=value;textSize=size;setTextColor(0xFFE1ECFC.toInt())}
    private fun surface(fill:Int,stroke:Int)=GradientDrawable().apply{cornerRadius=dp(16).toFloat();setColor(fill);setStroke(dp(1),stroke)}
    private fun button(value:String,action:()->Unit)=label(value,16f).apply{
        id=View.generateViewId();gravity=Gravity.CENTER;isFocusable=true;isFocusableInTouchMode=true;isClickable=true
        background=StateListDrawable().apply{
            addState(intArrayOf(android.R.attr.state_focused),surface(0xFF3768AD.toInt(),0xFFC1DAFF.toInt()))
            addState(intArrayOf(),surface(0xFF263649.toInt(),0xFF46566B.toInt()))
        }
        setOnClickListener{action()}
    }
    override fun onEvaluateFullscreenMode()=false
    override fun onEvaluateInputViewShown():Boolean{super.onEvaluateInputViewShown();return true}
    override fun onCreateInputView():View {
        window?.window?.apply{
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        root=FrameLayout(this).apply{setPadding(dp(20),0,dp(20),dp(14))}
        card=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;setPadding(dp(18),dp(14),dp(18),dp(14))
            background=surface(0xF21A293C.toInt(),0xFF647C9D.toInt())
        }
        val editor=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        editor.addView(label("Luma keyboard · type here or scan with your phone",16f))
        preview=EditText(this).apply{
            id=View.generateViewId();setSingleLine();textSize=17f;setTextColor(Color.WHITE)
            showSoftInputOnFocus=false;isFocusable=false;isFocusableInTouchMode=false;isCursorVisible=false
            keyListener=null;setPadding(dp(8),0,dp(8),0);background=ColorDrawable(0x55203048)
            contentDescription="Current text"
        }
        editor.addView(preview,LinearLayout.LayoutParams(-1,dp(36)).apply{topMargin=dp(6)})
        keyboard=QwertyKeyboardView(this,preview,36,object:QwertyKeyboardView.Actions{
            override fun insert(value:String){
                val draft=wifiDraft
                if(draft!=null){if(draft.length+value.length<=64){wifiDraft=draft.substring(0,wifiCursor)+value+draft.substring(wifiCursor);wifiCursor+=value.length}}
                else currentInputConnection?.commitText(value,1)
                refreshPreview()
            }
            override fun delete(){
                val draft=wifiDraft
                if(draft!=null){if(wifiCursor>0){wifiDraft=draft.removeRange(wifiCursor-1,wifiCursor);wifiCursor--}}
                else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                refreshPreview()
            }
            override fun move(direction:Int){
                if(wifiDraft!=null)wifiCursor=(wifiCursor+direction).coerceIn(0,wifiDraft!!.length)
                else sendDownUpKeyEvents(if(direction<0)KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                refreshPreview()
            }
            override fun clear(){
                if(wifiDraft!=null){wifiDraft="";wifiCursor=0}
                else currentInputConnection?.let{if(it.performContextMenuAction(android.R.id.selectAll))it.commitText("",1)}
                refreshPreview()
            }
        })
        editor.addView(keyboard,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        val buttons=LinearLayout(this)
        wifiPaste=button("Save Wi-Fi password"){
            if(wifiDraft!=null){wifiDraft=null;updateWifiControls();refreshPreview()}
            else if(savedWifiPassword()!=null)pasteWifiPassword()else editWifiPassword()
        }.apply{visibility=View.GONE}
        buttons.addView(wifiPaste,LinearLayout.LayoutParams(0,dp(38),1.5f).apply{rightMargin=dp(8)})
        go=button("Go"){confirm()}
        hide=button("Close"){requestHideSelf(0)}
        buttons.addView(go,LinearLayout.LayoutParams(0,dp(38),1f));buttons.addView(hide,LinearLayout.LayoutParams(0,dp(38),1f).apply{leftMargin=dp(8)})
        editor.addView(buttons,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        card.addView(editor,LinearLayout.LayoutParams(0,-2,1f))
        val phone=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(16),0,0,0)}
        phoneTitle=label("Type on your phone",16f);phone.addView(phoneTitle)
        qr=ImageView(this).apply{contentDescription="Scan to type on your phone";setBackgroundColor(Color.WHITE);setPadding(dp(5),dp(5),dp(5),dp(5));visibility=View.INVISIBLE}
        phone.addView(qr,LinearLayout.LayoutParams(dp(164),dp(164)).apply{topMargin=dp(8)})
        status=label("Preparing QR code…").apply{gravity=Gravity.CENTER;minHeight=dp(58)}
        phone.addView(status,LinearLayout.LayoutParams(-1,-2))
        val refresh=button("New QR code"){startPhone()}
        phoneRefresh=refresh
        phone.addView(refresh,LinearLayout.LayoutParams(-1,dp(38)))
        wifiChange=button("Change password"){editWifiPassword()}.apply{visibility=View.GONE}
        wifiForget=button("Forget password"){
            if(wifiField){getSharedPreferences("wifi_keyboard_private",MODE_PRIVATE).edit().remove("prefill").apply();updateWifiControls();wifiPaste.requestFocus()}
        }.apply{visibility=View.GONE}
        phone.addView(wifiChange,LinearLayout.LayoutParams(-1,dp(38)))
        phone.addView(wifiForget,LinearLayout.LayoutParams(-1,dp(38)).apply{topMargin=dp(6)})
        card.addView(phone,LinearLayout.LayoutParams(dp(208),-2))
        keyboard.connectFocus(go,hide,refresh)
        go.nextFocusRightId=hide.id;hide.nextFocusLeftId=go.id;refresh.nextFocusDownId=go.id
        val width=minOf(dp(1100),resources.displayMetrics.widthPixels-dp(40))
        root.addView(card,FrameLayout.LayoutParams(width,-2,Gravity.CENTER_HORIZONTAL))
        return root
    }
    // Keep the application's layout intact and let the keyboard float over its lower area.
    override fun onComputeInsets(outInsets:Insets){
        super.onComputeInsets(outInsets)
        if(!::card.isInitialized)return
        val height=window?.window?.decorView?.height?:resources.displayMetrics.heightPixels
        outInsets.contentTopInsets=height;outInsets.visibleTopInsets=height
        outInsets.touchableInsets=Insets.TOUCHABLE_INSETS_REGION
        if(phoneTyping){outInsets.touchableRegion.setEmpty();return}
        val location=IntArray(2);card.getLocationInWindow(location)
        outInsets.touchableRegion.set(Rect(location[0],location[1],location[0]+card.width,location[1]+card.height))
    }
    override fun onStartInput(info:EditorInfo?,restarting:Boolean){
        wifiDraft=null
        stopPhone();editing=false
        super.onStartInput(info,restarting)
        wifiField=info?.packageName=="com.yhk.setting" && WifiPasswordAutofill.matches(info.packageName,runCatching{
            packageManager.getResourcesForApplication(info.packageName).getResourceEntryName(info.fieldId)
        }.getOrNull())
        secret=wifiField || isPassword(info?.inputType?:0)
        info?.packageName?.takeIf{it!=packageName}?.let{lastExternalPackage=it;lastExternalSecret=secret}
        // The P6 settings overlay focuses this editor without asking Android to show an IME.
        if(wifiField && android.os.Build.VERSION.SDK_INT>=28)main.postDelayed({
            if(wifiField && currentInputEditorInfo?.packageName=="com.yhk.setting")requestShowSelf(0)
        },250)
    }
    override fun onStartInputView(info:EditorInfo?,restarting:Boolean){
        super.onStartInputView(info,restarting);editing=true
        phoneTyping(false)
        updateWifiControls()
        wifiPaste.nextFocusRightId=go.id;wifiPaste.nextFocusUpId=keyboard.firstKey.id
        go.nextFocusLeftId=if(wifiField)wifiPaste.id else View.NO_ID
        go.text=when(info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)){
            EditorInfo.IME_ACTION_SEARCH->getString(R.string.keyboard_search);EditorInfo.IME_ACTION_NEXT->getString(R.string.keyboard_next);EditorInfo.IME_ACTION_SEND->getString(R.string.keyboard_send);else->getString(R.string.keyboard_go)
        }
        keyboard.connectFocus(go,hide,if(wifiField)wifiChange.takeIf{it.visibility==View.VISIBLE}?:wifiPaste else phoneRefresh)
        refreshPreview();(if(wifiField)wifiPaste else keyboard.firstKey).requestFocus();startPhone();updateWifiControls()
    }
    private fun savedWifiPassword()=getSharedPreferences("wifi_keyboard_private",MODE_PRIVATE)
        .getString("prefill",null)?.takeIf{WifiPasswordAutofill.valid(it)}
    private fun updateWifiControls(){
        if(!::wifiPaste.isInitialized)return
        val saved=wifiField && savedWifiPassword()!=null
        wifiPaste.visibility=if(wifiField)View.VISIBLE else View.GONE
        wifiPaste.text=if(wifiDraft!=null)"Cancel" else if(saved)"Paste Wi-Fi password" else "Save Wi-Fi password"
        wifiChange.visibility=if(saved && wifiDraft==null)View.VISIBLE else View.GONE
        wifiForget.visibility=wifiChange.visibility
        phoneRefresh.visibility=if(wifiField)View.GONE else View.VISIBLE
        phoneTitle.text=if(wifiField)"Saved Wi-Fi password" else "Type on your phone"
        if(wifiField){qr.visibility=View.GONE;status.text=if(wifiDraft!=null)"Enter with the remote, then Save. Stored only on this projector." else "Private to this projector. Paste never connects automatically."}
        wifiChange.nextFocusDownId=wifiForget.id;wifiForget.nextFocusUpId=wifiChange.id
        wifiChange.nextFocusLeftId=go.id;wifiForget.nextFocusLeftId=go.id
        if(wifiField)go.text=if(wifiDraft!=null)"Save" else "Go"
    }
    private fun editWifiPassword(){
        if(!editing || !wifiField)return
        stopPhone();wifiDraft="";wifiCursor=0;updateWifiControls();refreshPreview();keyboard.firstKey.requestFocus()
    }
    private fun pasteWifiPassword(){
        if(!editing || !wifiField)return
        val value=savedWifiPassword()?:return
        val connection=currentInputConnection?:return
        // Explicit paste replaces the current entry; never submits a connection.
        if(connection.performContextMenuAction(android.R.id.selectAll))connection.commitText(value,1)
        refreshPreview()
    }
    override fun onFinishInputView(finishingInput:Boolean){wifiDraft=null;editing=false;stopPhone();super.onFinishInputView(finishingInput)}
    override fun onFinishInput(){wifiDraft=null;editing=false;stopPhone();super.onFinishInput()}
    override fun onWindowHidden(){wifiDraft=null;editing=false;stopPhone();super.onWindowHidden()}
    override fun onDestroy(){editing=false;stopPhone();worker.shutdownNow();super.onDestroy()}
    override fun onUpdateSelection(oldSelStart:Int,oldSelEnd:Int,newSelStart:Int,newSelEnd:Int,candidatesStart:Int,candidatesEnd:Int){
        super.onUpdateSelection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd)
        if(editing)refreshPreview()
    }
    private fun snapshot():String?{
        if(secret)return null
        val extracted=currentInputConnection?.getExtractedText(ExtractedTextRequest().apply{hintMaxChars=2049;hintMaxLines=100},0)?:return null
        if(extracted.startOffset!=0 || extracted.partialStartOffset>=0)return null
        return extracted.text?.toString()?.takeIf{it.length<=2048}
    }
    private fun refreshPreview(){
        if(!::preview.isInitialized)return
        val text=wifiDraft?.let{"New password: "+"•".repeat(it.length)}?:if(secret)"Secure field · use the remote" else snapshot()
        preview.setText(text?:"Type with the remote in the app's field")
        if(!secret && text!=null)session?.updateText(text)
    }
    private fun replaceFromPhone(value:String):Boolean{
        if(!editing || secret)return false
        val previous=snapshot()?:return false
        val connection=currentInputConnection?:return false
        connection.beginBatchEdit()
        val result=try{connection.finishComposingText();connection.setSelection(0,previous.length) && connection.commitText(value,1)}
            finally{connection.endBatchEdit()}
        refreshPreview();return result
    }
    private fun confirm(){
        if(!editing)return
        wifiDraft?.let{draft->
            if(!WifiPasswordAutofill.valid(draft)){
                android.widget.Toast.makeText(this,"Use 8–63 characters, or a 64-digit hexadecimal Wi-Fi key",android.widget.Toast.LENGTH_LONG).show();return
            }
            getSharedPreferences("wifi_keyboard_private",MODE_PRIVATE).edit().putString("prefill",draft).apply()
            wifiDraft=null;updateWifiControls();refreshPreview();wifiPaste.requestFocus();return
        }
        if(!sendDefaultEditorAction(false))sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        if(currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)!=EditorInfo.IME_ACTION_NEXT)requestHideSelf(0)
    }
    private fun stopPhone(){
        generation++;session?.let{PhoneRemoteControl.detach(it)};session=null
        if(::qr.isInitialized){qr.setImageDrawable(null);qr.tag=null;qr.visibility=View.INVISIBLE}
        if(::preview.isInitialized)preview.setText("")
    }
    private fun startPhone(){
        stopPhone();refreshPreview()
        if(!editing)return
        val initial=snapshot()
        if(secret || initial==null){status.setText(if(secret)R.string.keyboard_secure else R.string.keyboard_no_shared_text);return}
        status.setText(R.string.keyboard_preparing)
        val request=generation
        worker.execute{
            var created:PhoneTextSession?=null
            val result=runCatching{
                val manager=getSystemService(ConnectivityManager::class.java)
                val address=manager.getLinkProperties(manager.activeNetwork)?.linkAddresses?.map{it.address}?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull{!it.isLoopbackAddress && !it.isLinkLocalAddress}?:error("Connect to Wi-Fi for phone typing")
                val page=assets.open("phone-keyboard.html").bufferedReader().use{it.readText()}
                val next=PhoneTextSession(address,page,"Type in the current app",2048,initial,onText={value->main.post{
                    if(editing && generation==request){phoneTyping(true);if(replaceFromPhone(value))status.setText(R.string.phone_keyboard_connected) else {phoneTyping(false);stopPhone();status.setText(R.string.keyboard_field_changed)}}
                }},onExpired={main.post{if(generation==request){phoneTyping(false);stopPhone();status.setText(R.string.keyboard_expired)}}},onSubmit={value->main.post{
                    if(editing && generation==request && replaceFromPhone(value))confirm()
                }},onRemote=PhoneRemoteControl.callback(this),icon=assets.open("luma-remote-icon.png").use{it.readBytes()},onInputReady={FastPhoneInput.ready()},onFocusNavigation={FastPhoneInput.usesNativeFocus()},onMagneticTarget={PhoneMouseCursor.magneticTarget()},onApps={PhoneRemoteControl.apps(this)},onLaunch={PhoneRemoteControl.launch(this,it)},onConnected={main.post{if(editing && generation==request)phoneTyping(true)}})
                PhoneRemoteControl.register(next)
                created=next
                val phoneUrl=PhoneRemoteService.url(this)?:next.url
                val matrix=QRCodeWriter().encode(phoneUrl,BarcodeFormat.QR_CODE,360,360,mapOf(EncodeHintType.MARGIN to 3))
                val pixels=IntArray(matrix.width*matrix.height){i->if(matrix[i%matrix.width,i/matrix.width])Color.BLACK else Color.WHITE}
                next to Bitmap.createBitmap(pixels,matrix.width,matrix.height,Bitmap.Config.ARGB_8888)
            }
            main.post{
                if(!editing || generation!=request){created?.close();return@post}
                result.onSuccess{(next,bitmap)->session=next;next.updateText(snapshot()?:initial);qr.setImageBitmap(bitmap);qr.tag=PhoneRemoteService.url(this)?:next.url;qr.visibility=View.VISIBLE;status.setText(R.string.phone_keyboard_ready)}
                    .onFailure{created?.close();status.setText(R.string.phone_keyboard_unavailable)}
            }
        }
    }
    private fun handleRemote(keyCode:Int,event:KeyEvent):Boolean{
        if(!editing || !isInputViewShown || !::root.isInitialized)return false
        if(phoneTyping){
            if(event.deviceId>=0 && keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_CENTER))phoneTyping(false)
            else return false
        }
        val direction=when(keyCode){KeyEvent.KEYCODE_DPAD_LEFT->View.FOCUS_LEFT;KeyEvent.KEYCODE_DPAD_RIGHT->View.FOCUS_RIGHT;KeyEvent.KEYCODE_DPAD_UP->View.FOCUS_UP;KeyEvent.KEYCODE_DPAD_DOWN->View.FOCUS_DOWN;else->0}
        val select=keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER)
        if(direction==0 && !select)return false
        if(event.action==KeyEvent.ACTION_DOWN){
            val focused=root.findFocus()?:keyboard.firstKey.also{it.requestFocus()}
            if(direction!=0)focused.focusSearch(direction)?.requestFocus()
        }else if(select && !event.isCanceled)(root.findFocus()?:keyboard.firstKey).performClick()
        return true
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean=handleRemote(keyCode,event)||super.onKeyDown(keyCode,event)
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean=handleRemote(keyCode,event)||super.onKeyUp(keyCode,event)
    companion object {
        private var instance:java.lang.ref.WeakReference<LumaInputMethodService>?=null
        private var lastExternalSecret=false
        fun secureExternal(source:String?)=source!=null && source==lastExternalPackage && lastExternalSecret
        fun submitOverlay(source:String,text:String)=instance?.get()?.acceptOverlay(source,text)==true
        @Volatile var lastExternalPackage:String?=null
            private set
        fun isPassword(type:Int):Boolean{
            val variation=type and InputType.TYPE_MASK_VARIATION
            return when(type and InputType.TYPE_MASK_CLASS){
                InputType.TYPE_CLASS_TEXT->variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                InputType.TYPE_CLASS_NUMBER->variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else->false
            }
        }
    }
}
