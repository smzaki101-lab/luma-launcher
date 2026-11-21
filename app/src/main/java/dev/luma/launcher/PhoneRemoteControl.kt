package dev.luma.launcher

import android.content.Context

/** Fixed remote actions over this P6's existing loopback control transport. */
object PhoneRemoteControl {
    @Volatile private var pairingDialog=java.lang.ref.WeakReference<android.app.Dialog>(null)
    fun pairing(dialog:android.app.Dialog){pairingDialog=java.lang.ref.WeakReference(dialog)}
    fun connected(){
        retained?.phoneConnected()
        val dialog=pairingDialog.get()?:return
        pairingDialog.clear()
        android.os.Handler(android.os.Looper.getMainLooper()).post{if(dialog.isShowing)dialog.dismiss()}
    }
    private var cachedApps:String?=null
    private var cachedAppsAt=0L
    @Synchronized fun apps(context:Context):String{
        val now=android.os.SystemClock.elapsedRealtime()
        cachedApps?.let{if(now-cachedAppsAt<30000L)return it}
        val repository=AppRepository(context.applicationContext)
        val prefs=Preferences(context.applicationContext)
        return org.json.JSONArray().apply{
            repository.discover().filter{AppVisibility.visible(it,prefs)}.forEach{app->
                val entry=org.json.JSONObject().put("id",app.id).put("name",repository.displayName(app))
                runCatching{
                    val drawable=context.packageManager.getApplicationIcon(app.id)
                    val bitmap=android.graphics.Bitmap.createBitmap(96,96,android.graphics.Bitmap.Config.ARGB_8888)
                    try{
                        val canvas=android.graphics.Canvas(bitmap)
                        drawable.setBounds(0,0,96,96);drawable.draw(canvas)
                        val bytes=java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes)
                        entry.put("icon","data:image/png;base64,"+android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP))
                    }finally{bitmap.recycle()}
                }
                put(entry)
            }
        }.toString().also{cachedApps=it;cachedAppsAt=now}
    }
    fun launch(context:Context,id:String):Boolean{
        if(id.length>255)return false
        val repository=AppRepository(context.applicationContext)
        val prefs=Preferences(context.applicationContext)
        val app=repository.discover().firstOrNull{it.id==id && AppVisibility.visible(it,prefs)}?:return false
        return repository.launch(app).also{if(it)RecentApps.record(prefs,app.id)}
    }
    @Volatile private var retained:PhoneTextSession?=null
    fun textTarget():PhoneTextSession?=retained?.takeIf{it.isOpen}
    fun callback(context:Context):((String)->Boolean)? {
        val app=context.applicationContext
        if(!FastPhoneInput.prepare(app))return null
        return {command->
            if(command=="controlCentre" || command=="appSwitcher"){
                runCatching{
                    app.startActivity(android.content.Intent(app,GlobalControlActivity::class.java).apply{
                        putExtra("phone_app_switcher",command=="appSwitcher")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    });true
                }.getOrDefault(false)
            }else if(command=="apps" || command=="recents" || command=="home"){
                runCatching{
                    app.startActivity(android.content.Intent(app,LauncherActivity::class.java).apply{
                        action=if(command=="home") android.content.Intent.ACTION_MAIN else LauncherActivity.ACTION_GLOBAL_CONTROL
                        if(command!="home") putExtra(LauncherActivity.EXTRA_GLOBAL_TARGET,if(command=="settings") "projector_settings" else "all_apps")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    })
                    true
                }.getOrDefault(false)
            }else{
            if(command=="scrollUp" || command=="scrollDown" || command.startsWith("scroll:"))UniversalPointer.pause()
            if((command.startsWith("move:") || command.startsWith("tap:")) && !FastPhoneInput.usesNativeFocus())PhoneMouseCursor.move(app,command)
            val sent=FastPhoneInput.command(app,command)
            if(FastPhoneInput.usesNativeFocus())UniversalPointer.pause()
            sent
            }
        }
    }

    @Synchronized fun register(session:PhoneTextSession){retained?.takeIf{it!==session}?.close();retained=session}
    @Synchronized fun detach(session:PhoneTextSession){
        if(retained===session && session.isOpen && session.hasRemote)session.detachText() else session.close()
    }
}
