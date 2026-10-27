package dev.luma.launcher

import android.app.Activity
import android.app.ActivityManager
import android.content.*
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class LauncherActivity : Activity() {
    companion object {
        const val ACTION_GLOBAL_CONTROL="dev.luma.launcher.GLOBAL_CONTROL"
        const val EXTRA_GLOBAL_TARGET="target"
    }
    lateinit var preferences: Preferences; private set
    lateinit var home: HomeScreen; private set
    private lateinit var root: FrameLayout
    private lateinit var wallpaper: WallpaperView
    private lateinit var wallpapers: WallpaperRepository
    private lateinit var apps: AppRepository
    private lateinit var saver: Screensaver
    private lateinit var weather: Weather
    private val io=Executors.newSingleThreadExecutor()
    private val traktIo=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var installed=emptyList<AppEntry>()
    private var pendingGlobalTarget:String?=null
    private var panel: GlassPanel?=null
    private var focusBeforePanel: View?=null
    private var switcher:AppSwitcherView?=null
    private var switcherRequest=0
    private var switcherPending=false
    private var expanded:TopShelfExpandedView?=null
    private var continuing:ContinueWatchingView?=null
    private var cinematic:CinematicContinueView?=null
    private var backdrop:CinematicBackdropView?=null
    private var backdropArt:android.graphics.Bitmap?=null
    private var backdropFrost:android.graphics.Bitmap?=null
    private var exitBackdrop:CinematicBackdropView?=null
    private var cinematicClosing=false
    private var cinematicExitGeneration=0
    private var cinematicWallpaper:android.graphics.Bitmap?=null
    private var cinematicFrost:android.graphics.Bitmap?=null
    private var cinematicImageRequest=0
    private lateinit var trakt:TraktClient
    private lateinit var traktImages:TraktImageCache
    private var continueItems=emptyList<ContinueItem>()
    private var watchNextItems=emptyList<ContinueItem>()
    private var closingPanel:GlassPanel?=null
    private var panelBack: () -> Unit = { closePanel() }
    private var resumed=false
    private var stopNetworkObserve:(()->Unit)?=null
    private var scanning=false
    private var loadedWallpaper="\u0000"
    private var selectedApp: AppEntry?=null
    internal var searchDialog: android.app.Dialog?=null
    internal var folderNameDialog: android.app.Dialog?=null
    private var pickTarget=""
    private var pickProfile=0
    private var longSelect=false
    private var selectTarget: View?=null
    private var suppressWakeKey=-1
    private var menuDown=false
    private var menuHeld=false
    private val menuHold=Runnable {
        if(menuDown){menuHeld=true;appSwitcher()}
    }
    private var artworkTask: Runnable?=null
    private val longPress=Runnable {
        selectTarget?.let { v ->
            longSelect=true;v.isPressed=false
            val id=home.focusedId
            if(id.startsWith("@folder:") || (v as? AppTile)?.item!=null)home.beginMove(id)
        }
    }
    private val inactivity=Runnable { if(resumed && panel==null) saver.start() }
    private val clockTick=object : Runnable {
        override fun run() { if(resumed) {updateClock();main.postDelayed(this,60000-System.currentTimeMillis()%60000)} }
    }
    private val packageChanges=object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { apps.clear();home.releaseArtwork();refreshApps() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        LocalMenuService.resumeIfEnabled(this)
        if(intent?.action==ACTION_GLOBAL_CONTROL)pendingGlobalTarget=intent.getStringExtra(EXTRA_GLOBAL_TARGET)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        immersive()
        preferences=Preferences(this);apps=AppRepository(this);wallpapers=WallpaperRepository(this)
        trakt=TraktClient(preferences,SecureStore(this,preferences.profile),traktIo)
        traktImages=TraktImageCache(this)
        root=FrameLayout(this);wallpaper=WallpaperView(this);root.addView(wallpaper,FrameLayout.LayoutParams(-1,-1))
        home=HomeScreen(this,preferences,wallpaper,apps,io);root.addView(home,FrameLayout.LayoutParams(-1,-1))
        saver=Screensaver(this,root,preferences,wallpaper)
        weather=Weather(this){text,description->home.weatherText(text,description)}
        setContentView(root)
        home.onLaunch={launch(it)};home.onContext={contextMenu(it)};home.onControl={controlCentre()}
        home.onFolderOpen={home.showFolder(installed,it)};home.onFolderContext={folderOptions(it)}
        home.onApps={appsSection(true)};home.onAllApps={home.showLibrary(installed,home.focusedId)};home.onFocusApp={app -> selectedApp=app;updateArtwork()}
        home.onMove={from,to -> moveApp(from,to)}
        home.onTopShelf={app->expandShelf(app)}
        home.onCinematicContinue={app->openCinematic(app)}
        home.onContinue={showContinue()}
        pickTarget=state?.getString("pickTarget")?:"";pickProfile=state?.getInt("pickProfile")?:preferences.profile
        val filter=IntentFilter().apply {addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_CHANGED);addDataScheme("package")}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(packageChanges,filter,Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(packageChanges,filter)
        refreshApps();updateArtwork();updateClock()
    }
    override fun onSaveInstanceState(outState: Bundle) {outState.putString("pickTarget",pickTarget);outState.putInt("pickProfile",pickProfile);super.onSaveInstanceState(outState)}
    private val mediaHistoryChanges=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,_->
        if(resumed){loadContinue();cinematic?.updateMusic(continueItems)}
    }
    override fun onResume() {
        super.onResume();weather.start();resumed=true;immersive();home.alpha=1f;home.scaleX=1f;home.scaleY=1f
        stopNetworkObserve=NetworkState.observe(this){updateClock();panel?.setNetworkStatus(it.detail)}
        main.removeCallbacks(clockTick);main.post(clockTick);resetInactivity();refreshApps();home.reloadArtwork()
        loadContinue();refreshWatchNext();syncContinue(false)
        MediaResumeSource.connect(this)
        getSharedPreferences("media_resume",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(mediaHistoryChanges)
    }
    override fun onPause() {
        getSharedPreferences("media_resume",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(mediaHistoryChanges)
        switcherRequest++;switcherPending=false
        if(cinematic!=null)closeCinematic(false)
        stopNetworkObserve?.invoke();stopNetworkObserve=null
        weather.stop();resumed=false;main.removeCallbacks(inactivity);main.removeCallbacks(clockTick);main.removeCallbacks(longPress);main.removeCallbacks(menuHold)
        menuDown=false;menuHeld=false
        selectTarget=null;saver.stop(false);home.animate().cancel();super.onPause()
    }
    override fun onStop() {
        // Keep the bounded tile cache warm for a quick return from streaming apps.
        // Actual memory pressure still evicts it in onTrimMemory.
        home.releaseArtwork();wallpaper.releaseTransition()
        super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(!::home.isInitialized)return
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            apps.clear();wallpapers.trimMemory();wallpaper.releaseTransition()
            if(!resumed)home.releaseArtwork()
        }
        if(level==android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level==android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level>=android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            preferences.memoryPressure=true;invalidateGlass(root)
        }
    }
    override fun onLowMemory() {super.onLowMemory();onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)}
    override fun onDestroy() {
        folderNameDialog?.dismiss();weather.close();trakt.cancel();main.removeCallbacksAndMessages(null);unregisterReceiver(packageChanges);io.shutdownNow();traktIo.shutdownNow();wallpapers.close();super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if(intent.action==ACTION_GLOBAL_CONTROL){
            pendingGlobalTarget=intent.getStringExtra(EXTRA_GLOBAL_TARGET)
            main.post{handleGlobalTarget()}
            return
        }
        if(intent.action==Intent.ACTION_MAIN) {closePanel(false);saver.stop(false);home.finishMove();home.rebuild(installed,false)}
    }
    private fun handleGlobalTarget(){
        val target=pendingGlobalTarget?:return
        if(installed.isEmpty())return
        pendingGlobalTarget=null
        closePanel(false);saver.stop(false)
        when(target){
            "all_apps"->home.showLibrary(installed)
            "app_switcher"->appSwitcher()
            "search"->searchApps()
            "wallpaper"->wallpaperMenu()
            "screensaver"->saver.start()
            "more"->moreControls()
            "global_menu_settings"->globalControlSettings()
        }
    }
    @Suppress("DEPRECATION") private fun immersive() {
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    private fun refreshApps() {
        if(isDestroyed)return
        if(scanning){scanAgain=true;return}
        scanning=true
        io.execute {
            val result=runCatching { apps.discover() }
            main.post {
                scanning=false;if(isDestroyed)return@post
                result.onSuccess { next ->
                    val changed=next!=installed;installed=next;RecentApps.reconcile(preferences,next)
                    if(changed || home.childCount==0 || !initializedGrid) {
                        initializedGrid=true;home.rebuild(installed,home.allApps)
                        if(panel!=null) {home.descendantFocusability=android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS;panel?.focusAt()}
                    }
                    home.reloadArtwork()
                    handleGlobalTarget()
                }.onFailure { toast("Could not read installed apps. Open system settings and try again.") }
                if(scanAgain){scanAgain=false;refreshApps()}
            }
        }
    }
    private var initializedGrid=false
    private var scanAgain=false
    private fun rebuild(restore: String=home.focusedId) {home.rebuild(installed,home.allApps,restore);updateClock();updateArtwork();invalidateGlass(root)}
    private fun updateClock() {
        val time=android.text.format.DateFormat.getTimeFormat(this).format(Date())
        home.clockText(time,NetworkState.current)
        panel?.setClockText(time)
    }
    private fun updateArtwork() {
        if(cinematic!=null)return
        artworkTask?.let {main.removeCallbacks(it)}
        val candidate=selectedApp?.takeIf{TopShelfContent.list(preferences,it.id).isNotEmpty()}
            ?.let {preferences.text("shelf:${it.id}")}.orEmpty().ifBlank {preferences.text("wallpaper")}
        wallpaper.darkness=if(preferences.number("dark",0)==1) .30f else .08f
        artworkTask=Runnable {
            if(candidate==loadedWallpaper)return@Runnable
            loadedWallpaper=candidate
            wallpapers.load(candidate,if(preferences.renderMode==2)1280 else 1920) { bitmap,frost ->
                val current=selectedApp?.takeIf{TopShelfContent.list(preferences,it.id).isNotEmpty()}
                    ?.let{preferences.text("shelf:${it.id}")}.orEmpty().ifBlank{preferences.text("wallpaper")}
                if(current!=candidate || cinematic!=null)return@load
                if(bitmap!=null && frost!=null) {wallpaper.show(bitmap,frost,preferences.motion!=0);invalidateGlass(root)}
                else {
                    toast("Artwork unavailable. Using the included landscape.")
                    wallpapers.load("") { fallback,soft -> if(fallback!=null && soft!=null) {wallpaper.show(fallback,soft);invalidateGlass(root)} }
                }
            }
        }.also{main.postDelayed(it,100)}
    }
    private fun invalidateGlass(view: View) {view.invalidate();if(view is android.view.ViewGroup) for(i in 0 until view.childCount)invalidateGlass(view.getChildAt(i))}
    private fun resetInactivity() {
        main.removeCallbacks(inactivity)
        val minutes=preferences.number("saverMinutes",5)
        if(resumed && panel==null && minutes>0) main.postDelayed(inactivity,minutes*60000L)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(event.keyCode==KeyEvent.KEYCODE_HOME)return super.dispatchKeyEvent(event)
        if(panel==null && switcher?.handleKeyEvent(event)==true)return true
        if(event.keyCode==KeyEvent.KEYCODE_APP_SWITCH){if(event.action==KeyEvent.ACTION_UP && switcher==null)appSwitcher();return true}
        if(event.keyCode==suppressWakeKey) {if(event.action==KeyEvent.ACTION_UP)suppressWakeKey=-1;return true}
        if(saver.active && event.action==KeyEvent.ACTION_DOWN) {saver.stop();suppressWakeKey=event.keyCode;resetInactivity();return true}
        if(event.action==KeyEvent.ACTION_DOWN)resetInactivity()
        cinematic?.let {view->
            if(cinematicClosing && event.keyCode==KeyEvent.KEYCODE_DPAD_UP){
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)reverseCinematicExit()
                return true
            }
            if(event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER)){
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)when(event.keyCode){
                    KeyEvent.KEYCODE_DPAD_LEFT->{if(cinematicClosing)reverseCinematicExit();view.step(-1)}
                    KeyEvent.KEYCODE_DPAD_RIGHT->{if(cinematicClosing)reverseCinematicExit();view.step(1)}
                    KeyEvent.KEYCODE_DPAD_DOWN->closeCinematic()
                    else->if(!cinematicClosing)view.openCurrent()
                }
                return true
            }
        }
        val select=event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER)
        if(select && selectTarget!=null) {
            if(event.action==KeyEvent.ACTION_UP) {
                main.removeCallbacks(longPress);val target=selectTarget;selectTarget=null
                target?.isPressed=false;if(!longSelect && !event.isCanceled)target?.performClick()
            }
            return true
        }
        if(select && panel==null && currentFocus is AppTile && event.action==KeyEvent.ACTION_DOWN) {
            selectTarget=currentFocus;longSelect=false;selectTarget?.isPressed=true
            if(((currentFocus as AppTile).item!=null || home.focusedFolder!=null) && home.moveId==null)main.postDelayed(longPress,550)
            return true
        }
        // The P6 IR remote's ☰ button is mapped to MENU by its vendor key layout.
        // Keep it active even when the optional shortcut is set to Info or Play/Pause.
        if(event.keyCode==KeyEvent.KEYCODE_MENU){
            if(event.action==KeyEvent.ACTION_DOWN && !menuDown){
                menuDown=true;menuHeld=false;main.postDelayed(menuHold,550)
            }else if(event.action==KeyEvent.ACTION_DOWN && !menuHeld &&
                (event.isLongPress || event.eventTime-event.downTime>=550)){
                main.removeCallbacks(menuHold);menuHeld=true;appSwitcher()
            }else if(event.action==KeyEvent.ACTION_UP && menuDown){
                main.removeCallbacks(menuHold);menuDown=false
                if(!menuHeld && !event.isCanceled){if(panel==null)controlCentre()else closePanel()}
                menuHeld=false
            }
            return true
        }
        if(event.keyCode==preferences.shortcut){
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) {if(panel==null)controlCentre()else closePanel()}
            return true
        }
        if(event.keyCode==KeyEvent.KEYCODE_BACK) {
            if(event.action==KeyEvent.ACTION_UP){
                if(switcherPending){switcherRequest++;switcherPending=false}else onBackPressed()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if(event.action==android.view.MotionEvent.ACTION_DOWN) {resetInactivity();if(saver.active){saver.stop();return true}}
        return super.dispatchTouchEvent(event)
    }
    @Deprecated("Android TV remote back handling") override fun onBackPressed() {
        when {saver.active -> saver.stop();switcher!=null ->closeSwitcher();expanded!=null ->closeExpanded();cinematic!=null ->closeCinematic();continuing!=null ->closeContinue();panel!=null -> panelBack();home.moveId!=null ->home.finishMove()
            home.currentFolderId!=null ->home.showLibrary(installed,"@folder:${home.currentFolderId}")
            home.allApps ->home.rebuild(installed,false);else ->home.focusFirst()}
    }
    private fun launch(app: AppEntry) {
        preferences.put("focus",app.id)
        home.animate().cancel()
        home.animate().alpha(.55f).scaleX(1.012f).scaleY(1.012f).setDuration(if(preferences.motion==0)0 else 110).withEndAction {
            if(apps.launch(app))RecentApps.record(preferences,app.id)
            else {home.alpha=1f;home.scaleX=1f;home.scaleY=1f;toast("Could not open ${app.label}. It may have been removed or restricted.");refreshApps()}
        }.start()
    }
    private fun showPanel(title: String, actions: List<PanelAction>, subtitle: String="${preferences.profileName} · Back to return", focus: Int=0, back: ()->Unit={closePanel()}, compact:Boolean=false) {
        main.removeCallbacks(inactivity)
        if(panel==null)focusBeforePanel=currentFocus?.takeIf{it.isDescendantOf(home)}
        if(title=="Control Centre")home.suppressClock(true)
        closingPanel?.let{root.removeView(it)};closingPanel=null
        panel?.let {root.removeView(it)}
        home.descendantFocusability=android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        panel=GlassPanel(this,wallpaper,preferences,title,subtitle,actions,compact,
            if(title=="Control Centre")PanelAction(NetworkState.current.detail){openSettings(Settings.ACTION_WIFI_SETTINGS)} else null).also {
            root.addView(it,FrameLayout.LayoutParams(-1,-1));it.enter(preferences.motion!=0);it.focusAt(focus)
        }
        panelBack=back
    }
    private fun closePanel(animated: Boolean=true) {
        val previous=panel;panel=null
        if(previous!=null) {
            previous.isEnabled=false;previous.descendantFocusability=android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            closingPanel?.let{root.removeView(it)};closingPanel=previous
            previous.leave(animated && preferences.motion!=0){root.removeView(previous);if(closingPanel===previous)closingPanel=null}
        }
        home.suppressClock(false)
        home.descendantFocusability=android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        val restore=focusBeforePanel;focusBeforePanel=null
        if(restore?.isAttachedToWindow==true && restore.isFocusable)restore.requestFocus() else home.focusGrid()
        resetInactivity()
    }
    private fun View.isDescendantOf(parent: ViewGroup):Boolean {
        var node:View?=this
        while(node!=null){if(node===parent)return true;node=node.parent as? View}
        return false
    }
    private fun action(title: String, block: ()->Unit)=PanelAction(title,block)
    private fun controlCentre(focus:Int=0) {
        if(switcher!=null)closeSwitcher(false)
        showPanel("Control Centre",listOf(
            action("All Apps") {closePanel(false);home.showLibrary(installed)},
            action("App Switcher") {appSwitcher()},
            action("Keyboard") {searchApps()},
            action("Audio settings") {openSettings(Settings.ACTION_SOUND_SETTINGS)},
            action("Wallpaper") {wallpaperMenu()},
            action("Start screensaver") {closePanel(false);saver.start()},
            action("More controls") {moreControls()}
        ),"${preferences.profileName} · ${networkStatus()}",focus=focus)
        panel?.setClockText(android.text.format.DateFormat.getTimeFormat(this).format(Date()))
    }
    private fun moreControls(){
        showPanel("More controls",listOf(
            action("Launcher settings  ›") {settings()},
            action("Selected tile  ›") {home.focusedApp?.let{contextMenu(it)} ?: home.focusedFolder?.let{folderOptions(it)} ?:toast("Focus an app tile first.")},
            action("Continue Watching  ›") {showContinue()},
            action("Recently Used  ›") {recentApps()},
            action("Expand Top Shelf  ›") {home.focusedApp?.let{expandShelf(it)}?:toast("Focus an app tile first.")},
            action("Apps  ›") {appsSection()},
            action("${networkStatus()}  ›") {openSettings(Settings.ACTION_WIFI_SETTINGS)},
            action("Profiles · ${preferences.profileName}  ›") {profiles()},
            action("Projector settings  ›") {openProjectorSettings()},
            action("Display settings  ›") {openSettings(Settings.ACTION_DISPLAY_SETTINGS)},
            action("Home app & recovery  ›") {recovery()},
            action("Close") {closePanel()}
        ),"Additional controls · Back to Control Centre",back={controlCentre(6)})
    }
    private fun appSwitcher(){
        if(switcher!=null || switcherPending)return
        val entries=if(RecentApps.enabled(preferences))RecentApps.installed(preferences,installed) else emptyList()
        if(entries.isEmpty()){toast("Open an app from Luma to use App Switcher.");return}
        closePanel(false);main.removeCallbacks(inactivity)
        wallpaper.visibility=View.VISIBLE;home.visibility=View.VISIBLE
        for(i in root.childCount-1 downTo 0)if(root.getChildAt(i) is AppSwitcherView)root.removeViewAt(i)
        val request=++switcherRequest
        switcherPending=true
        if(Build.VERSION.SDK_INT<26 || root.width==0 || root.height==0 || wallpaper.bitmap==null || !home.readyForSwitcherSnapshot()){
            showSwitcher(entries,null,request);return
        }
        // PixelCopy captures the real rendered Home, including hardware layers.
        // A half-size copy is sufficient beneath the Switcher's dark glass.
        root.postOnAnimation{
            if(!switcherPending || request!=switcherRequest || !resumed)return@postOnAnimation
            val image=runCatching{android.graphics.Bitmap.createBitmap(maxOf(1,root.width/2),maxOf(1,root.height/2),android.graphics.Bitmap.Config.ARGB_8888)}.getOrNull()
            if(image==null){showSwitcher(entries,null,request);return@postOnAnimation}
            try{
                android.view.PixelCopy.request(window,image,{result->
                    if(!switcherPending || request!=switcherRequest || !resumed)return@request
                    showSwitcher(entries,if(result==android.view.PixelCopy.SUCCESS)image else null,request)
                },main)
            }catch(_:RuntimeException){showSwitcher(entries,null,request)}
        }
    }
    private fun showSwitcher(entries:List<AppEntry>,frozen:android.graphics.Bitmap?,request:Int){
        if(!switcherPending || request!=switcherRequest)return
        switcherPending=false
        wallpaper.visibility=View.VISIBLE;home.visibility=View.VISIBLE
        for(i in root.childCount-1 downTo 0)if(root.getChildAt(i) is AppSwitcherView)root.removeViewAt(i)
        if(frozen!=null){wallpaper.visibility=View.INVISIBLE;home.visibility=View.INVISIBLE}
        home.descendantFocusability=android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        switcher=AppSwitcherView(this,preferences,apps,io,entries,{app->closeSwitcher(false);launch(app)},{closeSwitcher()},frozen).also{root.addView(it,FrameLayout.LayoutParams(-1,-1))}
    }
    private fun closeSwitcher(animated:Boolean=true){
        val old=switcher?:return;switcher=null
        wallpaper.visibility=View.VISIBLE;home.visibility=View.VISIBLE
        if(animated && preferences.motion!=0)old.leave{root.removeView(old)}else root.removeView(old)
        home.descendantFocusability=android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS;home.focusGrid();resetInactivity()
    }
    private fun expandShelf(app:AppEntry){
        val items=TopShelfContent.list(preferences,app.id).filter{TopShelfContent.intent(this,app,it.openUri)!=null}
        if(items.isEmpty()){toast("No supported Top Shelf content is available for this app.");return}
        closePanel(false);main.removeCallbacks(inactivity)
        home.descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS
        expanded=TopShelfExpandedView(this,wallpaper,preferences,app,items,{uri->
                val intent=TopShelfContent.intent(this,app,uri)
                if(intent!=null){closeExpanded(false);openIntent(intent)}else toast("This app does not support that link on the P6.")
            },{closeExpanded()}).also{root.addView(it,FrameLayout.LayoutParams(-1,-1))}
    }
    private fun closeExpanded(animated:Boolean=true){
        val old=expanded?:return;expanded=null
        if(animated && preferences.motion!=0)old.leave{root.removeView(old)}else root.removeView(old)
        home.descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS;home.focusGrid();resetInactivity()
    }
    private fun loadContinue(){
        continueItems=if(preferences.number("continueEnabled",1)==1)
            (watchNextItems+MediaResumeSource.read(this)+ManualContinueStore.list(preferences)+trakt.cached).distinctBy{it.id}.filter{it.id !in preferences.list("continueHidden")} else emptyList()
        home.continueWatching(continueItems.size)
    }
    private fun refreshWatchNext(){
        if(!WatchNextSource.permitted(this))return
        io.execute{val items=WatchNextSource.read(this)+WatchNextSource.readStremioChannel(this);main.post{
            android.util.Log.i("LumaWatchNext","Luma read ${items.size} TV entries")
            watchNextItems=items;loadContinue()
        }}
    }
    private fun openCinematic(app:AppEntry):Boolean{
        if(app.id !in setOf("com.apple.atve.androidtv.appletv","com.amazon.amazonvideo.livingroom","com.disney.disneyplus","com.stremio.one") && app.id !in MediaResumePolicy.labels)return false
        val items=continueItems.filter{it.sourcePackage==app.id}
        if(items.isEmpty() && app.id in MediaResumePolicy.labels && !MediaResumeSource.enabled(this)){
            showPanel("Continue watching & listening",listOf(
                action("Enable Luma playback history"){openPlaybackAccessSettings()},
                action("Back"){closePanel()}
            ),"Allow Luma playback history in Android notification access, then play something in ${app.label}. Luma remembers reported media titles, artwork and progress on this projector only. Notification messages are not read. Account histories cannot be imported.")
            return true
        }
        if(items.isEmpty()){toast("No Continue Watching titles available for ${app.label}.");return true}
        openCinematicEntries(items,MediaResumePolicy.labels[app.id]=="Spotify" && items.size==1)
        return true
    }
    private fun openCinematicEntries(items:List<ContinueItem>,musicMode:Boolean=false){
        if(cinematic!=null)closeCinematic(false)
        closePanel(false);main.removeCallbacks(inactivity)
        artworkTask?.let{main.removeCallbacks(it)}
        cinematicWallpaper=wallpaper.bitmap;cinematicFrost=wallpaper.frost
        home.setCinematic(true);home.descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS
        cinematic=CinematicContinueView(this,preferences,wallpaper,traktImages,io,items,
            {item->showCinematicArtwork(item)},{item->openContinueItem(item)},{closeCinematic()},musicMode)
            .also{root.addView(it,FrameLayout.LayoutParams(-1,-1))}
        cinematic?.focusFirst()
    }
    private fun showCinematicArtwork(item:ContinueItem){
        val request=++cinematicImageRequest
        // Square music covers belong in the player, not stretched across a 16:9 TV.
        if(MediaResumePolicy.labels[item.sourcePackage]=="Spotify"){
            val base=cinematicWallpaper;val frost=cinematicFrost
            if(base!=null && frost!=null && (wallpaper.bitmap!==base || backdrop!=null))transitionBackdrop(base,frost)
            return
        }
        if(item.imageUrl.isBlank()){
            val base=cinematicWallpaper;val frost=cinematicFrost
            if(base!=null && frost!=null)transitionBackdrop(base,frost)
            return
        }
        io.execute{val art=runCatching{traktImages.load(item.imageUrl,1280)}.getOrNull()
            val youtube=MediaResumePolicy.labels[item.sourcePackage]=="YouTube"
            // Decode and soften off the UI thread; keep preview cards and labels sharp.
            val soft=art?.let{if(youtube)WallpaperRepository.soften(it)
                else android.graphics.Bitmap.createScaledBitmap(it,96,(it.height*96/it.width).coerceAtLeast(1),true)}
            main.post{if(request==cinematicImageRequest && cinematic!=null && !cinematicClosing){
                if(art!=null && soft!=null){
                    transitionBackdrop(if(youtube || art.width*1f/art.height<1.2f)soft else art,soft)
                }else{
                    val base=cinematicWallpaper;val frost=cinematicFrost
                    if(base!=null && frost!=null)transitionBackdrop(base,frost)
                }
            }}
        }
    }
    private fun transitionBackdrop(art:android.graphics.Bitmap,soft:android.graphics.Bitmap){
        if(cinematicClosing)return
        backdrop?.let{it.animate().cancel();root.removeView(it)};backdrop=null
        backdropArt=art;backdropFrost=soft
        if(preferences.motion==0){wallpaper.show(art,soft,false);backdropArt=null;backdropFrost=null;invalidateGlass(home);return}
        val layer=CinematicBackdropView(this,art,wallpaper.darkness)
        backdrop=layer;layer.alpha=0f
        root.addView(layer,1,FrameLayout.LayoutParams(-1,-1))
        layer.animate().alpha(1f).setDuration(300).withEndAction{
            if(backdrop===layer){wallpaper.show(art,soft,false);root.removeView(layer);backdrop=null;backdropArt=null;backdropFrost=null;invalidateGlass(home)}
        }.start()
    }
    private fun closeCinematic(animated:Boolean=true){
        val old=cinematic?:return
        if(cinematicClosing && animated)return
        cinematicImageRequest++
        val generation=++cinematicExitGeneration
        val base=cinematicWallpaper;val frost=cinematicFrost
        if(!animated || preferences.motion==0 || base==null || frost==null){
            old.animate().cancel();exitBackdrop?.let{it.animate().cancel();root.removeView(it)};exitBackdrop=null
            backdrop?.let{it.animate().cancel();root.removeView(it)};backdrop=null
            backdropArt=null;backdropFrost=null
            root.removeView(old);cinematic=null;cinematicClosing=false
            if(base!=null && frost!=null)wallpaper.show(base,frost,false)
            cinematicWallpaper=null;cinematicFrost=null
            home.setCinematic(false);home.descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS
            home.focusGrid();resetInactivity();return
        }
        cinematicClosing=true
        // Keep the current artwork and the entire dock in place until both fades complete.
        backdrop?.animate()?.cancel()
        var contentDone=false;var backgroundDone=wallpaper.bitmap===base && backdrop==null
        fun finish(){
            if(generation!=cinematicExitGeneration || !contentDone || !backgroundDone)return
            wallpaper.show(base,frost,false)
            exitBackdrop?.let{root.removeView(it)};exitBackdrop=null
            backdrop?.let{root.removeView(it)};backdrop=null;backdropArt=null;backdropFrost=null
            root.removeView(old);cinematic=null;cinematicClosing=false
            cinematicWallpaper=null;cinematicFrost=null
            home.setCinematic(false);home.descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS
            home.focusGrid();resetInactivity();invalidateGlass(home)
        }
        old.leave{contentDone=true;finish()}
        if(!backgroundDone){
            val layer=exitBackdrop ?: CinematicBackdropView(this,base,wallpaper.darkness).also{
                exitBackdrop=it;it.alpha=0f
                root.addView(it,root.indexOfChild(home),FrameLayout.LayoutParams(-1,-1))
            }
            layer.animate().cancel()
            layer.animate().alpha(1f).setInterpolator(DecelerateInterpolator(1.5f))
                .setDuration(300).withEndAction{backgroundDone=true;finish()}.start()
        }
    }
    private fun reverseCinematicExit(){
        if(!cinematicClosing)return
        ++cinematicExitGeneration;cinematicClosing=false
        cinematic?.returnToVisible()
        exitBackdrop?.let{layer->
            layer.animate().cancel()
            layer.animate().alpha(0f).setInterpolator(DecelerateInterpolator(1.5f))
                .setDuration(240).withEndAction{if(exitBackdrop===layer){root.removeView(layer);exitBackdrop=null}}.start()
        }
        val layer=backdrop;val art=backdropArt;val soft=backdropFrost
        if(layer!=null && art!=null && soft!=null){
            layer.animate().alpha(1f).setInterpolator(DecelerateInterpolator(1.5f))
                .setDuration(240).withEndAction{
                    if(backdrop===layer){wallpaper.show(art,soft,false);root.removeView(layer);backdrop=null
                        backdropArt=null;backdropFrost=null;invalidateGlass(home)}
                }.start()
        }else if(wallpaper.bitmap===cinematicWallpaper){
            cinematic?.currentItem()?.let{showCinematicArtwork(it)}
        }
    }
    private fun syncContinue(force:Boolean){
        if(!trakt.connected || preferences.number("continueEnabled",1)!=1)return
        val last=preferences.text("traktSyncAt").toLongOrNull()?:0L
        if(!force && System.currentTimeMillis()-last<20*60000)return
        trakt.refresh{items,error->
            if(items!=null){loadContinue();if(force)toast("Continue Watching updated · ${continueItems.size} titles")}
            else if(force)toast(error?:"Could not refresh Trakt. Showing cached titles.")
        }
    }
    private fun showContinue(){
        loadContinue()
        if(continueItems.isEmpty()){toast("No available Continue Watching titles. Add one in settings or allow Android TV listings.");return}
        openCinematicEntries(continueItems)
    }
    private fun openContinueItem(item:ContinueItem){
        if(cinematic!=null)closeCinematic(false)
        if(item.origin=="Local media session" && MediaResumeSource.resumeLive(this,item)){closeContinue(false);return}
        if(item.sourcePackage.isNotBlank()){
            val app=installed.firstOrNull{it.id==item.sourcePackage}
            if(app!=null){
                val link=item.openUri.takeIf{it.isNotBlank()}?.let{TopShelfContent.intent(this,app,it)}
                closeContinue(false)
                if(link!=null)openIntent(link)
                else {toast("Opening ${item.sourceLabel}; title-level opening is unavailable.");apps.launch(app)}
                return
            }
        }
        val stremio=installed.firstOrNull{it.id=="com.stremio.one"}
        val intent=if(stremio!=null && preferences.number("continueStremioLinks",1)==1 && item.stremioUri.isNotBlank())TopShelfContent.intent(this,stremio,item.stremioUri) else null
        if(intent!=null){closeContinue(false);openIntent(intent);return}
        toast("No supported title link is available. Open your streaming app to continue.")
    }
    private fun closeContinue(animated:Boolean=true){
        val old=continuing?:return;continuing=null
        if(animated && preferences.motion!=0)old.leave{root.removeView(old)}else root.removeView(old)
        home.descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS;home.focusGrid();resetInactivity()
    }
    private fun networkStatus(): String = NetworkState.current.detail
    private fun settings(focus: Int=0) {
        val modes=listOf("High fidelity","Balanced","Performance")
        showPanel("Launcher settings",listOf(
            action("Performance · ${modes[preferences.renderMode.coerceIn(0,2)]}${if(preferences.memoryPressure)" (memory saver)" else ""}"){choices("Performance","mode",modes,1){settings(0)}},
            action("Motion · ${listOf("Reduced","Normal","Expressive")[preferences.motion.coerceIn(0,2)]}"){choices("Motion","motion",listOf("Reduced","Normal","Expressive"),1){settings(1)}},
            action("Icon size  ›"){choices("Icon size","iconSize",listOf("Compact","Standard","Large"),1){settings(2)}},
            action("Icon spacing  ›"){choices("Icon spacing","gap",listOf("Close","Standard","Wide"),1){settings(3)}},
            action("Glass blur  ›"){choices("Glass blur","blur",listOf("Off","Soft","Standard","Strong"),2){settings(4)}},
            action("Appearance  ›"){appearanceSettings()},
            action("Screen safe area · ${preferences.number("safe",5)}%  ›"){safeArea()},
            action("Screensaver  ›"){screensaverSettings()},
            action("Control Centre shortcut  ›"){shortcutSettings()},
            action("Control Centre inside apps  ›"){globalControlSettings()},
            action("App Management  ›"){appManagement()},
            action("Profiles  ›"){profiles()},
            action("Device diagnostics  ›"){diagnostics()},
            action("Home app & recovery  ›"){recovery()},
            action("Restore default appearance"){preferences.resetAppearance();loadedWallpaper="\u0000";rebuild();settings(14)},
            action("Weather · ${weather.location}  ›"){weatherSettings()},
            action("Recently Used · ${if(RecentApps.enabled(preferences))"On" else "Off"}  ›"){recentSettings()},
            action("Continue Watching · ${if(!trakt.connected)"Not connected" else if(trakt.cached.isEmpty())"Unavailable" else "Ready"}  ›"){continueSettings()}
        ),back={controlCentre()},focus=focus)
    }
    private fun continueSettings(){
        val cached=trakt.cached
        val status=when{trakt.connected && cached.isEmpty()->"Trakt connected · No content available";trakt.connected->"Trakt connected";preferences.text("traktClientId").isNotBlank()->"Ready to connect";else->"Client ID needed"}
        val synced=preferences.text("traktSyncAt").toLongOrNull()?.let{DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(it))}?:"Never"
        val paused=cached.count{it.progress!=null};val next=cached.count{it.progress==null}
        showPanel("Continue Watching",listOf(
            action("Feature · ${if(preferences.number("continueEnabled",1)==1)"On" else "Off"}"){
                preferences.put("continueEnabled",1-preferences.number("continueEnabled",1));loadContinue();continueSettings()
            },
            action("Enter Trakt Client ID  ›"){
                GlassNameDialog.show(this,wallpaper,preferences,"Trakt Client ID",preferences.text("traktClientId"),128){value->
                    preferences.put("traktClientId",value.trim());continueSettings()
                }
            },
            action("Connect Trakt on another device  ›"){
                if(trakt.connected){toast("Disconnect first to connect a different account.");return@action}
                toast("Requesting a Trakt code…")
                trakt.begin{code,error->
                    when{code!=null->showPanel("Connect Trakt",listOf(
                        action("Refresh connection status"){if(trakt.connected){syncContinue(true);continueSettings()}else toast("Waiting for approval on Trakt.")},
                        action("Cancel connection"){trakt.cancel();continueSettings()}
                    ),"On a phone or computer, visit ${code.url} and enter code ${code.code}.\nApprove Luma's access there. The code expires in ${code.expiresIn/60} minutes.",back={trakt.cancel();continueSettings()})
                    error!=null->{toast(error);continueSettings()}
                    else->{syncContinue(true);continueSettings()}
                    }
                }
            },
            action("Refresh watch history"){syncContinue(true)},
            action("Manage your titles  ›"){manualContinueSettings()},
            action("Android TV Watch Next · ${if(WatchNextSource.permitted(this))"On" else "Permission needed"}  ›"){
                if(WatchNextSource.permitted(this)){refreshWatchNext();toast("Refreshing Android TV Watch Next")}
                else requestPermissions(arrayOf(WatchNextSource.PERMISSION),901)
            },
            action("View Continue Watching  ›"){showContinue()},
            action("YouTube, Prime & Spotify playback · ${if(MediaResumeSource.enabled(this))"On" else "Enable"}  ›"){
                showPanel("Local playback history",listOf(
                    action("Open Android notification access") {openPlaybackAccessSettings()},
                    action("Clear this profile's playback history"){MediaResumeSource.clear(this);loadContinue();continueSettings()},
                    action("Back"){continueSettings()}
                ),"Enable Luma playback history in Android notification access. Luma reads media sessions only for YouTube, TizenTube, Prime Video and Spotify; notification messages are not read. New playback on this projector is remembered privately. It cannot import account histories. If an exact resume link is unavailable, the card reopens the app. Disable Android access to stop recording.",back={continueSettings()})
            },
            action("Stremio title links · ${if(preferences.number("continueStremioLinks",1)==1)"On" else "Off"}"){
                preferences.put("continueStremioLinks",1-preferences.number("continueStremioLinks",1));continueSettings()
            },
            action("Sync and service status  ›"){
                showPanel("Integration status",listOf(action("Back"){continueSettings()}),
                    "Android TV listings: ${watchNextItems.size} accessible titles. Apple TV: ${watchNextItems.count{it.sourceLabel=="Apple TV"}}. Prime Video: ${watchNextItems.count{it.sourceLabel=="Prime Video"}}. Disney+: ${watchNextItems.count{it.sourceLabel=="Disney+"}}. Stremio: ${watchNextItems.count{it.sourceLabel=="Stremio"}}. Trakt last sync: $synced; $paused paused titles, $next next episodes. Providers without accessible entries can use manually managed titles.",back={continueSettings()})
            },
            action("Show hidden titles"){preferences.putList("continueHidden",emptyList());loadContinue();continueSettings()},
            action("OAuth redirect URI  ›"){
                GlassNameDialog.show(this,wallpaper,preferences,"Trakt redirect URI",preferences.text("traktRedirectUri"),200){value->
                    preferences.put("traktRedirectUri",value.trim());continueSettings()
                }
            },
            action("Disconnect and clear cached history"){
                trakt.disconnect();continueItems=emptyList();home.continueWatching(0);continueSettings()
            }
        ),"$status · Android TV Watch Next and Trakt are separate optional sources. Progress appears only when supplied. A title link opens details or playback only when its app resolves it; exact resume is not promised.",back={settings(17)})
    }
    private fun manualContinueSettings(){
        val providers=listOf("com.apple.atve.androidtv.appletv" to "Apple TV",
            "com.amazon.amazonvideo.livingroom" to "Prime Video","com.disney.disneyplus" to "Disney+",
            "com.stremio.one" to "Stremio").filter{pair->installed.any{it.id==pair.first}}
        val entries=ManualContinueStore.list(preferences)
        showPanel("Your titles",providers.map{(pkg,label)->action("Add to $label  ›"){
            GlassNameDialog.show(this,wallpaper,preferences,"Title · $label","",100){name->
                ManualContinueStore.add(preferences,pkg,label,name);loadContinue();manualContinueSettings()
            }
        }}+entries.map{item->action("${item.sourceLabel} · ${item.title}  ›"){editManualTitle(item)}},
            "Titles you add are marked as yours. Luma does not invent playback progress. Select a title below to add episode details, artwork or a supported content link.",back={continueSettings()})
    }
    private fun editManualTitle(item:ContinueItem){
        fun changed(next:ContinueItem){ManualContinueStore.update(preferences,next);loadContinue();editManualTitle(next)}
        showPanel(item.title,listOf(
            action("Rename  ›"){GlassNameDialog.show(this,wallpaper,preferences,"Title",item.title,100){changed(item.copy(title=it))}},
            action("Episode or detail  ›"){GlassNameDialog.show(this,wallpaper,preferences,"Episode / detail",item.subtitle,100){changed(item.copy(subtitle=it))}},
            action("Clear episode detail"){changed(item.copy(subtitle=""))},
            action("Artwork HTTPS URL  ›"){GlassNameDialog.show(this,wallpaper,preferences,"Artwork URL",item.imageUrl,300){changed(item.copy(imageUrl=it))}},
            action("Clear artwork"){changed(item.copy(imageUrl=""))},
            action("Content link  ›"){GlassNameDialog.show(this,wallpaper,preferences,"Supported app link",item.openUri,300){changed(item.copy(openUri=it))}},
            action("Clear content link"){changed(item.copy(openUri=""))},
            action("Remove title"){ManualContinueStore.remove(preferences,item.id);loadContinue();manualContinueSettings()}
        ),"${item.sourceLabel} · Added by you. HTTPS artwork from Apple, Trakt, TMDB, Amazon or Stremio is supported. A title link opens only if ${item.sourceLabel} accepts it; without a link, Luma opens the app. Playback position is not recorded.",back={manualContinueSettings()})
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==901){refreshWatchNext();continueSettings()}
    }
    private fun weatherSettings(){
        showPanel("Weather",listOf(
            action("Location · ${weather.location}  ›"){
                GlassNameDialog.show(this,wallpaper,preferences,"Weather location",weather.location,80){query->
                    toast("Finding locations…")
                    weather.search(query){places->
                        if(places==null){toast("Location search unavailable. Try again later.");weatherSettings()}
                        else if(places.isEmpty()){toast("No matching locations. Try a nearby city.");weatherSettings()}
                        else showPanel("Choose location",places.map{place->action(place.name){weather.select(place);weatherSettings()}},back={weatherSettings()})
                    }
                }
            },
            action("Use London, UK"){weather.select(Weather.Place("London, UK",51.5074,-0.1278));weatherSettings()}
        ),"Weather by Open-Meteo · °C\nRefreshes every 20 minutes while Luma is open.\nLast successful reading is cached for offline use.",back={settings(15)})
    }
    private fun appearanceSettings(){
        showPanel("Appearance",listOf(
            action("Wallpaper  ›"){wallpaperMenu()},
            action("Background contrast  ›"){choices("Background contrast","dark",listOf("Cinematic","Darker"),0){appearanceSettings()}},
            action("Glass transparency  ›"){choices("Glass transparency","opacity",listOf("Lighter","Balanced","More opaque"),1){appearanceSettings()}},
            action("Glass colour  ›"){choices("Glass colour","tint",listOf("Neutral","Cool","Warm"),0){appearanceSettings()}},
            action("Blur intensity  ›"){choices("Blur intensity","blur",listOf("Off","Soft","Standard","Strong"),2){appearanceSettings()}}
        ),"Materials are shared across the dock and menus.",back={settings(5)})
    }
    private fun choices(title: String,key: String,names: List<String>,fallback: Int,back: ()->Unit) {
        val selected=preferences.number(key,fallback).coerceIn(0,names.lastIndex)
        showPanel(title,names.mapIndexed {index,name->action("${if(index==selected)"✓  " else ""}$name") {
            preferences.put(key,index);if(key=="mode"){preferences.memoryPressure=false;loadedWallpaper="\u0000"};rebuild();back()
        }},focus=selected,back=back)
    }
    private fun safeArea() {
        showPanel("Screen safe area",(0..8).map{value->action("$value%${if(value==preferences.number("safe",5))"  ✓" else ""}"){
            preferences.put("safe",value);rebuild();settings(6)
        }},"Inset controls if the edges of your projection are cropped.",preferences.number("safe",5),back={settings(6)})
    }
    private fun wallpaperMenu() {
        showPanel("Wallpaper",listOf(
            action("Choose local image or video  ›"){pick("wallpaper","image/*")},
            action("Use included Fjord landscape"){preferences.put("wallpaper","");preferences.put("video","");loadedWallpaper="\u0000";updateArtwork();closePanel()},
            action("Use Blue Hour Coast"){preferences.put("wallpaper","builtin:blue_hour_coast");preferences.put("video","");loadedWallpaper="\u0000";updateArtwork();closePanel()},
            action("Use Midnight Fjord"){preferences.put("wallpaper","builtin:midnight_fjord");preferences.put("video","");loadedWallpaper="\u0000";updateArtwork();closePanel()},
            action("Back"){controlCentre()}
        ),"Choose an included wallpaper, local photo, or video with screensaver playback. App artwork can override the first row.",back={controlCentre()})
    }
    private fun screensaverSettings() {
        val selected=preferences.number("saverMinutes",5)
        showPanel("Screensaver",listOf(
            action("Start now"){closePanel(false);saver.start()},
            action("Motion · ${if(preferences.number("saverMotion",if(preferences.motion==0)0 else 1)==0)"Still" else "Dynamic"}  ›"){
                preferences.put("saverMotion",if(preferences.number("saverMotion",if(preferences.motion==0)0 else 1)==0)1 else 0);screensaverSettings()
            },
            action("Idle timeout · ${if(selected==0)"Off" else "$selected minutes"}"){
                showPanel("Idle timeout",listOf(0,1,2,5,10,20,30).map{n->action(if(n==0)"Off" else "$n minutes"){
                    preferences.put("saverMinutes",n);screensaverSettings()
                }},back={screensaverSettings()})
            },
            action("Use wallpaper for projector idle screen  ›"){selectWallpaperScreensaver()}
        ),"Shows your wallpaper without app tiles. Select it for the projector's idle screen to replace its factory screensaver too. Any remote press wakes it.",back={settings(7)})
    }
    private fun selectWallpaperScreensaver(){
        val settings=Intent(Settings.ACTION_DREAM_SETTINGS)
        if(settings.resolveActivity(packageManager)!=null){openIntent(settings,false);return}
        // P6 firmware omits Android's chooser. Use only its existing local root debugging.
        io.execute{
            val result=runCatching{
                val adb=LoopbackAdb()
                check(adb.request("shell:id -u").trim()=="0")
                val component="dev.luma.launcher/.WallpaperDreamService"
                check(adb.request("shell:settings put secure screensaver_components $component && settings put secure screensaver_enabled 1 && settings get secure screensaver_components").trim()==component)
            }
            main.post{if(!isDestroyed){
                toast(if(result.isSuccess)"Wallpaper selected for the projector screensaver"
                    else "Could not select screensaver. Enable Projector-only Menu and try again.")
            }}
        }
    }
    private fun shortcutSettings() {
        val options=listOf("Menu" to KeyEvent.KEYCODE_MENU,"Play / Pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,"Info" to KeyEvent.KEYCODE_INFO)
        showPanel("Control Centre shortcut",options.map{(name,key)->action("$name${if(preferences.shortcut==key)"  ✓" else ""}"){preferences.put("shortcut",key);settings(8)}},
            "Menu always works while Luma is open. Play/Pause or Info can also be used here. The optional inside-app service handles Menu only.",back={settings(8)})
    }
    private fun globalControlSettings(){
        val manager=getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val enabled=manager.isEnabled && manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any{it.resolveInfo.serviceInfo.packageName==packageName &&
                it.resolveInfo.serviceInfo.name==GlobalControlService::class.java.name}
        val overlayAllowed=Settings.canDrawOverlays(this)
        val shortcutHelp=if(LocalMenuService.enabled(this))
            "Projector-only Menu is enabled. Luma restores the P6 remote shortcut after startup. Check its notification for connection status. Overlay permission is needed to show Control Centre over apps."
        else "${if(enabled)"Shortcut listed in Android settings" else "Accessibility shortcut off"} · ${if(overlayAllowed)"Overlay allowed" else "Overlay permission needed"}. The accessibility shortcut requires both permissions. The P6 can instead use Projector-only Menu or the external bridge. Luma does not read app screen content."
        showPanel("Control Centre in apps",listOf(
            action("Projector-only Menu · ${if(LocalMenuService.enabled(this))"On" else "Off"}  ›"){
                if(LocalMenuService.enabled(this))LocalMenuService.disable(this)
                else LocalMenuService.enable(this)
                main.postDelayed({globalControlSettings()},300)
            },
            action("Display over other apps · ${if(overlayAllowed)"Allowed" else "Allow"}  ›"){
                val intent=Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"))
                if(!openIntent(intent,false))openSettings(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            },
            action("Open Android Accessibility settings  ›"){openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)},
            action("Back"){settings(9)}
        ),shortcutHelp,back={settings(9)})
    }
    private fun profiles() {
        val active=preferences.profile
        val names=(0..2).map{n->preferences.profile=n;preferences.profileName}.also{preferences.profile=active}
        showPanel("Profiles",names.mapIndexed {index,name->action("$name${if(index==active)"  ✓" else ""}"){
            trakt.cancel();preferences.profile=index;trakt=TraktClient(preferences,SecureStore(this,index),traktIo)
            home.finishMove();selectedApp=null;loadedWallpaper="\u0000";home.rebuild(installed,false);loadContinue();syncContinue(false);updateArtwork();updateClock();controlCentre()
        }}+listOf(
            action("Rename ${preferences.profileName}  ›"){renameProfile()},
            action("Avatar symbol  ›"){choices("Avatar","avatar",listOf("Circle ●","Diamond ◆","Orbit ◉","Star ✦"),0){profiles()}}
        ),"Local layouts and preferences only. Streaming accounts are managed inside each app.",back={controlCentre()})
    }
    private fun renameProfile() {
        GlassNameDialog.show(this,wallpaper,preferences,"Profile name",preferences.profileName,24){name->preferences.put("name",name);profiles()}
    }
    private fun recentSettings(){
        showPanel("Recently Used",listOf(
            action("Tracking · ${if(RecentApps.enabled(preferences))"On" else "Off"}"){preferences.put("recentEnabled",if(RecentApps.enabled(preferences))0 else 1);recentSettings()},
            action("Clear launcher history"){RecentApps.clear(preferences);recentSettings()},
            action("View recently used  ›"){recentApps()}
        ),"Only apps opened through Luma are recorded for this profile.",back={settings(16)},compact=true)
    }
    private fun recentApps(){
        val entries=if(RecentApps.enabled(preferences))RecentApps.installed(preferences,installed) else emptyList()
        closePanel(false);home.showCollection(installed,"Recently Used",entries.map{it.id})
        if(entries.isEmpty())toast(if(RecentApps.enabled(preferences))"Apps opened from Luma will appear here." else "Turn on Recently Used in Launcher settings.")
    }
    private fun searchApps(){
        searchDialog=GlassNameDialog.show(this,wallpaper,preferences,"Search apps","",60){query->
            searchDialog=null
            val term=query.trim()
            val matches=installed.filter{AppVisibility.visible(it,preferences) && (apps.displayName(it).contains(term,true) || it.label.contains(term,true))}
            closePanel(false);home.showCollection(installed,if(term.isEmpty())"All Apps" else "Search · $term",matches.map{it.id})
            if(matches.isEmpty())toast("No visible apps match your search.")
        }
        searchDialog?.setOnDismissListener{searchDialog=null}
    }
    private fun contextMenu(app:AppEntry){
        val dock=AppVisibility.dock(installed,preferences)
        val visible=AppVisibility.visible(app,preferences)
        showPanel(apps.displayName(app),listOf(
            action("Open"){closePanel(false);launch(app)},
            action(if(app.id in dock)"Remove from dock" else "Add to dock"){
                if(app.id in dock)preferences.putList("dock",dock-app.id)
                else if(dock.size>=6){toast("The dock holds six apps. Remove one first.");return@action}
                else {preferences.put("visibility:${app.id}","show");preferences.putList("dock",dock+app.id)}
                rebuild(app.id);contextMenu(app)
            },
            action("Move app"){preferences.put("visibility:${app.id}","show");closePanel(false);home.rebuild(installed,true,app.id);home.beginMove(app.id)},
            action("Move to folder  ›"){chooseFolder(app)},
            action("Create folder  ›"){createFolder(app)},
            (Folders.containing(preferences,app.id)?.let { folder -> action("Remove from ${folder.name}"){removeFromFolder(app,folder)} }
                ?: action("Organise in folders  ›"){chooseFolder(app)}),
            action(if(visible)"Hide from library" else "Show in library"){preferences.put("visibility:${app.id}",if(visible)"hide" else "show");rebuild();closePanel()},
            action("Change tile artwork  ›"){tileIdentityOptions(app)},
            action("Expand Top Shelf  ›"){expandShelf(app)},
            action("Android app information  ›"){openIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${app.id}")))},
            action("More app options  ›"){appOptions(app)}
        ),"Back returns to the selected tile.",back={closePanel()},compact=true)
    }
    private fun appOptions(app: AppEntry) {
        val visible=AppVisibility.visible(app,preferences)
        val dock=AppVisibility.dock(installed,preferences)
        showPanel(apps.displayName(app),listOf(
            action("Open"){closePanel(false);launch(app)},
            action(if(visible)"Hide from entertainment library" else "Show in entertainment library"){
                preferences.put("visibility:${app.id}",if(visible)"hide" else "show");rebuild();appOptions(app)
            },
            action("Use automatic visibility"){
                preferences.put("visibility:${app.id}","");preferences.putList("hidden",preferences.list("hidden")-app.id)
                preferences.putList("removed",preferences.list("removed")-app.id);rebuild();appOptions(app)
            },
            action(if(app.id in dock)"Remove from dock" else "Add to dock"){
                if(app.id in dock)preferences.putList("dock",preferences.list("dock")-app.id)
                else if(dock.size>=6){toast("The dock holds six apps. Remove one from the dock first.");return@action}
                else {preferences.put("visibility:${app.id}","show");preferences.putList("dock",dock+app.id)}
                rebuild();appOptions(app)
            },
            action("Rearrange in library"){
                preferences.put("visibility:${app.id}","show");closePanel(false);home.rebuild(installed,true,app.id);home.beginMove(app.id)
            },
            action("Move to folder  ›"){chooseFolder(app)},
            action("Create folder  ›"){createFolder(app)}
        )+(if(app.id in dock)listOf(
            action("Move left in dock"){shiftDock(app.id,-1);appOptions(app)},
            action("Move right in dock"){shiftDock(app.id,1);appOptions(app)}
        )else emptyList())+listOf(
            action("Tile name and artwork  ›"){tileIdentityOptions(app)},
            action("Expand Top Shelf  ›"){expandShelf(app)},
            action("Add Top Shelf link  ›"){topShelfLink(app)},
            action("Choose Top Shelf artwork  ›"){pick("shelf:${app.id}","image/*")},
            action("Use wallpaper for Top Shelf"){preferences.put("shelf:${app.id}","");loadedWallpaper="\u0000";updateArtwork();appOptions(app)},
            action("Android app information  ›"){openIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${app.id}")))}
        ),"${AppVisibility.classify(app).title} · ${if(visible)"Visible" else "Hidden"}\n${if(app.isHome)"Home app remains available in recovery." else if(AppVisibility.classify(app) in listOf(AppCategory.MANUFACTURER,AppCategory.DEVELOPMENT,AppCategory.SYSTEM))"Projector utilities can affect device settings. Hiding only changes Luma." else "Visibility and artwork never change the installed app."}",back={appManagement()})
    }
    private fun shiftDock(id:String,delta:Int){
        val ids=AppVisibility.dock(installed,preferences).toMutableList();val i=ids.indexOf(id);val target=i+delta
        if(i>=0 && target in ids.indices){ids.removeAt(i);ids.add(target,id);preferences.putList("dock",ids);rebuild(id)}
    }
    private fun appsSection(returnToGrid:Boolean=false){
        val store=installed.firstOrNull{it.id=="com.android.vending"}
        showPanel("Apps",(if(store!=null)listOf(action("Open Google Play Store  ›"){closePanel(false);launch(store)})else emptyList())+listOf(
            action("Recently Used  ›"){recentApps()},
            action("Search apps  ›"){searchApps()},
            action("App Management  ›"){appManagement()},
            action("Hidden applications  ›"){managedApps(true)},
            action("All installed applications  ›"){managedApps(false)},
            action("Organise dock  ›"){dockManagement()}
        ),"Discover, install and organise your apps. Google Play opens separately.",back={if(returnToGrid)closePanel() else controlCentre()})
    }
    private fun appManagement(){
        val visible=installed.count{AppVisibility.visible(it,preferences)}
        showPanel("App Management",listOf(
            action("Entertainment library · $visible apps"){closePanel(false);home.showLibrary(installed)},
            action("All installed applications  ›"){managedApps(false)},
            action("Hidden applications  ›"){managedApps(true)},
            action("Organise dock  ›"){dockManagement()},
            action("Apps & installation  ›"){appsSection()}
        ),"Show, hide, rename and customise individual apps. Your choices are saved for this profile.",back={settings(10)})
    }
    private fun managedApps(hiddenOnly:Boolean){
        val entries=installed.filter{!hiddenOnly || !AppVisibility.visible(it,preferences)}
        showPanel(if(hiddenOnly)"Hidden applications" else "Installed applications",entries.map{app->
            action("${apps.displayName(app)} · ${if(AppVisibility.visible(app,preferences))"Visible" else "Hidden"}  ›"){appOptions(app)}
        }.ifEmpty{listOf(action("No hidden applications · Back"){appManagement()})},
            "${entries.size} apps · Select an app to manage it. System and projector utilities remain installed.",back={appManagement()})
    }
    private fun dockManagement(){
        val ids=AppVisibility.dock(installed,preferences)
        showPanel("Organise dock",ids.mapNotNull{id->installed.firstOrNull{it.id==id}}.map{app->action("${apps.displayName(app)}  ›"){appOptions(app)}}+listOf(
            action("Add an application  ›"){managedApps(false)},
            action("Restore default dock ordering"){
                val suggested=listOf("com.apple.atve.androidtv.appletv","com.google.android.youtube.tv","com.spotify.tv.android","com.stremio.one","com.amazon.amazonvideo.livingroom","com.netflix.ninja")
                val candidates=installed.filter{AppVisibility.visible(it,preferences)}
                preferences.putList("dock",(suggested+candidates.map{it.id}).distinct().filter{id->candidates.any{it.id==id}}.take(6));rebuild();dockManagement()
            }
        ),"Up to six favourites. Other visible apps stay in All Apps.",back={appManagement()})
    }
    private fun tileIdentityOptions(app:AppEntry) {
        showPanel("Tile identity",listOf(
            action("Rename ${apps.displayName(app)}  ›"){
                GlassNameDialog.show(this,wallpaper,preferences,"Tile display name",apps.displayName(app),40){name->
                    preferences.put("tileName:${app.id}",name);rebuild(app.id);tileIdentityOptions(app)
                }
            },
            action("Choose custom tile image  ›"){pick("tileArt:${app.id}","image/*")},
            action("Use installed artwork without glass finish"){
                preferences.put("tileStyle:${app.id}","original");preferences.put("tileArt:${app.id}","");apps.clear();home.releaseArtwork();rebuild(app.id);tileIdentityOptions(app)
            },
            action("Restore approved artwork and name"){
                preferences.put("tileStyle:${app.id}","");
                preferences.put("tileName:${app.id}","");preferences.put("tileArt:${app.id}","")
                apps.clear();home.releaseArtwork();rebuild(app.id);tileIdentityOptions(app)
            }
        ),"Opens ${app.label.ifBlank{app.id}} · ${app.id}\nNames and artwork only. The installed app is unchanged. Use a landscape image (5:3).",back={appOptions(app)})
    }
    private fun topShelfLink(app:AppEntry){
        val existing=TopShelfContent.list(preferences,app.id)
        showPanel("Top Shelf links",existing.map{item->action("Remove · ${item.title}"){
            TopShelfContent.save(preferences,app.id,existing-item);topShelfLink(app)
        }}+listOf(action("Add title and app link  ›"){
            GlassNameDialog.show(this,wallpaper,preferences,"Featured title","",80){title->
                if(title.isBlank()){topShelfLink(app);return@show}
                GlassNameDialog.show(this,wallpaper,preferences,"Supported app link (URL)","",300){uri->
                    if(TopShelfContent.intent(this,app,uri)==null)toast("This app does not support that link on the P6.")
                    else TopShelfContent.save(preferences,app.id,existing+TopShelfItem(title.trim(),"",uri.trim()))
                    topShelfLink(app)
                }
            }
        }),"Add only links this installed app supports. No catalogue or playback progress is invented.",back={appOptions(app)})
    }
    private fun createFolder(app:AppEntry) {
        folderNameDialog=GlassNameDialog.show(this,wallpaper,preferences,"Folder name","New Folder",32) { name ->
            val title=name.trim().ifEmpty{"New Folder"}
            val before=Folders.read(preferences)
            val folders=before.map{it.copy(members=it.members-app.id)}.filter{it.members.isNotEmpty()}.toMutableList()
            val id=java.util.UUID.randomUUID().toString()
            val folder=AppFolder(id,title,listOf(app.id))
            folders.add(folder);Folders.write(preferences,folders)
            val dock=preferences.list("dock").toMutableList()
            val previous=before.firstOrNull{app.id in it.members && folders.none{current->current.id==it.id}}
            val index=dock.indexOf(app.id).takeIf{it>=0}?:previous?.tileId?.let{dock.indexOf(it)}?:-1
            if(index>=0){dock[index]=folder.tileId;preferences.putList("dock",dock)}
            preferences.put("visibility:${app.id}","show")
            closePanel(false);home.showLibrary(installed,folder.tileId)
            folderOptions(folder)
        }
        folderNameDialog?.setOnDismissListener{folderNameDialog=null}
    }
    private fun chooseFolder(app:AppEntry) {
        val folders=Folders.read(preferences).filter{app.id !in it.members}
        showPanel("Move to folder",folders.map{folder->
            action(folder.name){moveIntoFolder(app,folder)}
        }+listOf(action("Create new folder  ›"){createFolder(app)}),
            "Choose a folder for ${apps.displayName(app)}.",back={contextMenu(app)},compact=true)
    }
    private fun moveIntoFolder(app:AppEntry,folder:AppFolder) {
        val before=Folders.read(preferences)
        val updated=before.map { existing ->
            when(existing.id){
                folder.id ->existing.copy(members=(existing.members+app.id).distinct())
                else ->existing.copy(members=existing.members-app.id)
            }
        }.filter{it.members.isNotEmpty()}
        Folders.write(preferences,updated)
        val vanished=(before.map{it.tileId}-updated.map{it.tileId}).toSet()
        preferences.putList("dock",preferences.list("dock").filter{it!=app.id && it !in vanished})
        preferences.put("visibility:${app.id}","show")
        closePanel(false);home.showFolder(installed,updated.first{it.id==folder.id})
    }
    private fun removeFromFolder(app:AppEntry,folder:AppFolder) {
        val updated=Folders.read(preferences).map{if(it.id==folder.id)it.copy(members=it.members-app.id)else it}.filter{it.members.isNotEmpty()}
        Folders.write(preferences,updated)
        val dock=preferences.list("dock").toMutableList()
        if(updated.none{it.id==folder.id}){
            val index=dock.indexOf(folder.tileId)
            if(index>=0)dock[index]=app.id
        }
        preferences.putList("dock",dock)
        closePanel(false);home.showLibrary(installed,app.id)
    }
    private fun folderOptions(folder:AppFolder) {
        val current=Folders.read(preferences).firstOrNull{it.id==folder.id}?:return
        val candidates=installed.filter{AppVisibility.visible(it,preferences) && it.id !in current.members}
        showPanel(current.name,listOf(
            action("Open folder"){closePanel(false);home.showFolder(installed,current)},
            action("Rename folder  ›"){
                GlassNameDialog.show(this,wallpaper,preferences,"Folder name",current.name,32){name->
                    Folders.write(preferences,Folders.read(preferences).map{if(it.id==current.id)it.copy(name=name.trim().ifEmpty{"Folder"})else it})
                    closePanel(false);home.showLibrary(installed,current.tileId)
                }
            },
            action("Add application  ›"){
                showPanel("Add to ${current.name}",candidates.map{app->action(apps.displayName(app)){moveIntoFolder(app,current)}}.ifEmpty{
                    listOf(action("No other visible apps"){folderOptions(current)})
                },"Select an app to place in this folder.",back={folderOptions(current)})
            },
            action("Move folder"){closePanel(false);home.showLibrary(installed,current.tileId);home.beginMove(current.tileId)},
            action("Remove folder · keep apps"){
                Folders.write(preferences,Folders.read(preferences).filter{it.id!=current.id})
                preferences.putList("dock",preferences.list("dock").map{if(it==current.tileId)current.members.first()else it})
                closePanel(false);home.showLibrary(installed,current.members.first())
            }
        ),"${current.members.size} apps · Hold OK on an app inside to arrange it.",back={closePanel()},compact=true)
    }
    private fun moveApp(from: String,to: String) {
        val folder=home.currentFolderId?.let{id->Folders.read(preferences).firstOrNull{it.id==id}}
        if(folder!=null && from in folder.members && to in folder.members){
            val ids=folder.members.toMutableList();ids.remove(from);ids.add(ids.indexOf(to),from)
            Folders.write(preferences,Folders.read(preferences).map{if(it.id==folder.id)it.copy(members=ids)else it})
            home.showFolder(installed,folder.copy(members=ids));home.beginMove(from);return
        }
        val folders=Folders.read(preferences)
        val nested=folders.flatMap{it.members}.toSet()
        val available=installed.map{it.id}.filter{it !in nested}+folders.map{it.tileId}
        val ids=GridNavigation.ordered(available,preferences.list("order"),preferences.list("favourites"),emptySet()).toMutableList()
        val a=ids.indexOf(from);val b=ids.indexOf(to)
        if(a<0 || b<0)return
        ids.removeAt(a);ids.add(b,from)
        preferences.putList("order",ids)
        val dock=preferences.list("dock").toMutableList()
        val da=dock.indexOf(from);val db=dock.indexOf(to)
        if(da>=0 && db>=0){dock.removeAt(da);dock.add(db,from);preferences.putList("dock",dock)}
        rebuild(from)
    }
    private fun layoutSettings() = appManagement()
    private fun recovery() {
        showPanel("Home app & recovery",listOf(
            action("Choose default Home app  ›"){openHomeSelection()},
            action("Luma app information  ›"){openIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}
        )+installed.filter{it.isHome}.map { app->action("Open ${app.label}"){closePanel(false);launch(app)} },
            "Luma · dev.luma.launcher\nOpening a launcher does not change the default.\nUse Android Home settings to change the default.",back={controlCentre()})
    }
    private fun openHomeSelection() {
        if(openIntent(Intent(Settings.ACTION_HOME_SETTINGS),false))return
        toast("This firmware has no direct Home selection screen. In Android settings, look for Apps → Default apps → Home app, or clear Luma’s defaults.")
        if(!openIntent(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),false))openSettings(Settings.ACTION_SETTINGS)
    }
    private fun openProjectorSettings() {
        val component=preferences.text("projectorSettings")
        if(component.isNotBlank()) {
            val target=ComponentName.unflattenFromString(component)
            if(target!=null && openIntent(Intent().setComponent(target),false))return
        }
        openSettings(Settings.ACTION_SETTINGS)
    }
    private fun diagnostics() {
        val dm=resources.displayMetrics
        val manager=getSystemService(ActivityManager::class.java)
        val info=ActivityManager.MemoryInfo();manager.getMemoryInfo(info)
        @Suppress("DEPRECATION") val refresh=windowManager.defaultDisplay.refreshRate
        val details="${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\n${dm.widthPixels} × ${dm.heightPixels} · density ${dm.densityDpi} · ${"%.1f".format(refresh)} Hz\nRAM ${info.totalMem/1048576} MB · app heap class ${manager.memoryClass} MB\n${Build.SUPPORTED_ABIS.joinToString()}\n${installed.size} launchable apps · Luma ${BuildConfigVersion.name}\n${networkStatus()}"
        showPanel("Device diagnostics",listOf(
            action("Android device information  ›"){openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS)},
            action("Select projector settings app  ›"){
                showPanel("Projector settings shortcut",installed.map{app->action(app.label){preferences.put("projectorSettings",app.component.flattenToString());diagnostics()}},
                    "Choose the installed app that opens your P6 controls. No private hardware APIs are used.",back={diagnostics()})
            },action("Back"){settings(12)}
        ),details,back={settings(12)})
    }
    private fun pick(target: String,type: String) {
        pickTarget=target;pickProfile=preferences.profile
        if(type.startsWith("image/")){
            startActivityForResult(Intent(this,ImagePickerActivity::class.java).putExtra("allow_video",target=="wallpaper"),42);return
        }
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {startActivityForResult(intent,41)} catch(_:ActivityNotFoundException) {toast("No document picker is installed. Install a remote-compatible file picker or use the included landscape.")}
    }
    @Deprecated("Platform document picker compatibility") override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode !in listOf(41,42) || resultCode!=RESULT_OK)return
        val uri=if(requestCode==42)data?.getStringExtra(ImagePickerActivity.EXTRA_IMAGE)?.let{Uri.parse(it)}else data?.data
        if(uri==null)return
        try {
            if(requestCode==41)contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val active=preferences.profile;preferences.profile=pickProfile;preferences.put(pickTarget,uri.toString());preferences.profile=active
            if(pickTarget=="wallpaper"){
                preferences.profile=pickProfile
                preferences.put("video",if(requestCode==42)data?.getStringExtra(ImagePickerActivity.EXTRA_VIDEO).orEmpty()else "")
                preferences.profile=active
            }
            if(pickTarget.startsWith("tileArt:")){apps.clear();home.releaseArtwork();rebuild(pickTarget.removePrefix("tileArt:"))}
            loadedWallpaper="\u0000";updateArtwork();closePanel(false);toast("Saved for this profile")
        } catch(_:SecurityException) {toast("This picker did not grant lasting file access. Choose a file with Android's system document picker.")}
    }
    private fun openSettings(action: String) {
        if(!openIntent(Intent(action),false) && !openIntent(Intent(Settings.ACTION_SETTINGS),false)) toast("This firmware does not expose that settings screen. Use the remote's settings button.")
    }
    private fun openPlaybackAccessSettings(){
        if(!openIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),false))
            toast("This TV firmware has no Notification access screen. Playback history remains off; Luma still works normally.")
    }
    private fun openIntent(intent: Intent,report: Boolean=true): Boolean = try {startActivity(intent);true} catch(_:RuntimeException) {
        if(report)toast("This action is unavailable on this firmware.");false
    }
    private fun toast(text: String) = Toast.makeText(this,text,Toast.LENGTH_LONG).show()
}
object BuildConfigVersion { const val name="0.6.1-alpha" }
