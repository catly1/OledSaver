package com.catly.oledsaver.features.floating_window

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.view.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.catly.oledsaver.R
import com.catly.oledsaver.features.floating_window.bar.BottomBar
import com.catly.oledsaver.features.floating_window.bar.LeftBar
import com.catly.oledsaver.features.floating_window.bar.RightBar
import com.catly.oledsaver.features.floating_window.bar.TopBar
import com.catly.oledsaver.features.main.MainActivity


class FloatingWindowService : Service() {

    private lateinit var sharedpreferences: SharedPreferences
    private val channelID = "OLED Blinds Service"
    lateinit var windowManager: WindowManager
    lateinit var powerManager: PowerManager
    lateinit var displayManager: DisplayManager
    lateinit var leftBar: LeftBar
    lateinit var rightBar: RightBar
    lateinit var topBar: TopBar
    lateinit var bottomBar: BottomBar
    var width: Int = 0
    var overrideWidthForTopBottom: Int = 0
    var height: Int = 0
    var locked = false
    var override = false
    var isActive = false
    var statusBarSize = 0
    var rotation = 0

    companion object {
        fun startService(context: Context) {
            val startIntent = Intent(context, FloatingWindowService::class.java)
            context.startForegroundService(startIntent)
        }

        fun stopService(context: Context) {
            val stopIntent = Intent(context, FloatingWindowService::class.java)
            context.stopService(stopIntent)
        }

        var isRunning = false
    }

    private var flipped = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener(){ sharedPreferences: SharedPreferences, key: String->
        when (key) {
            "override" -> {
                override = sharedPreferences.getBoolean(key, false)
                if (isActive) {
                    refresh()
                }
            }
            "isActive" -> {
                isActive = sharedPreferences.getBoolean(key, false)
            }
        }
    }

    private val displayListener: DisplayListener = object : DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
        }

        override fun onDisplayChanged(displayId: Int) {
            if (powerManager.isInteractive and override and flipped){
                handleLeftRightBarCutoutAdjustment()
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
        }
    }


    private fun refresh() {
        handleOverrideDimensions()
        if (flipped) {
            handleLeftRightBarCutoutAdjustment()
        } else {
            topBar.updateWidth(overrideWidthForTopBottom)
            bottomBar.updateWidth(overrideWidthForTopBottom)
        }
    }

    private fun handleLeftRightBarCutoutAdjustment(){
        if (override) {
            handleOverrideButton()
            when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_90 -> {
                    rotation = 90
                    rightBar.revertX()
                    leftBar.adjustForCutoff(statusBarSize)
                }
                Surface.ROTATION_270 -> {
                    rotation = 270
                    leftBar.revertX()
                    rightBar.adjustForCutoff(statusBarSize)
                }
            }
        } else {
            rightBar.hideOverrideButton()
            rightBar.disableOverrideButton()
        }
    }

    private fun handleOverrideButton(){
        rightBar.showOverrideButton()
        if (!locked){
            rightBar.enableOverrideButton()
        }
    }

    fun setAndUpdateOffset(offset: Int){
        statusBarSize = offset
        handleLeftRightBarCutoutAdjustment()
    }

    fun saveOffset(){
        sharedpreferences.edit().putString("statusBarSize", statusBarSize.toString()).apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)

        val notification = NotificationCompat.Builder(this, channelID)
            .setContentTitle("OLED Blinds")
            .setContentText("OLED Blinds is running.")
            .setSmallIcon(R.drawable.ic_stat_oledsaver)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
        return super.onStartCommand(intent, flags, startId)
    }

    private fun createNotificationChannel(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelID, "OLED Blinds Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager!!.createNotificationChannel(serviceChannel)
        }
    }

    private fun getPrefValuesAndSystemServices(){
        sharedpreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedpreferences.edit().putBoolean("isActive", true).apply()
        isActive = sharedpreferences.getBoolean("isActive", false)
        override = sharedpreferences.getBoolean("override", false)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        flipped = sharedpreferences.getBoolean("isFlipped", false)
        locked = sharedpreferences.getBoolean("isLocked", false)
        statusBarSize = sharedpreferences.getString("statusBarSize", "92")!!.toInt()
    }

    override fun onCreate() {
        super.onCreate()
        getPrefValuesAndSystemServices()
        setWidthHeightValues()
        handleOverrideDimensions()
        if (flipped){
            leftRightMode()
            handleLeftRightBarCutoutAdjustment()
        } else {
            topDownMode()
        }
        setLockState()
        sharedpreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        displayManager.registerDisplayListener(displayListener, Handler())
        isRunning = true
    }

    private fun setLockState(){
        if (locked){
            lockButtons()
        }
    }

    private fun handleOverrideDimensions(){
        overrideWidthForTopBottom = if (override){
            windowManager.defaultDisplay.width + statusBarSize * 2
        } else {
            MATCH_PARENT
        }
    }

    private fun setWidthHeightValues(){
        width = sharedpreferences.getInt("width", 200)
        width = if (checkIfValidNumber(width)){
            width
        } else {
            200
        }
        height = sharedpreferences.getInt("height", 200)
        height = if (checkIfValidNumber(height)){
            height
        } else {
            200
        }
    }

    private fun topDownMode(){
        bottomBar = BottomBar(this)
        topBar = TopBar(this)
        bottomBar.attach()
        topBar.attach()
    }

    private fun leftRightMode(){
        rightBar = RightBar(this)
        leftBar = LeftBar(this)
        leftBar.attach()
        rightBar.attach()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (flipped) {
            removeLeftRight()
        } else {
            removeTopBottom()
        }
        displayManager.unregisterDisplayListener(displayListener)
        sharedpreferences.edit().putBoolean("isActive", false).apply()
        isActive = false
    }

    private fun removeTopBottom(){
        bottomBar.remove()
        topBar.remove()
    }

    private fun removeLeftRight(){
        leftBar.remove()
        rightBar.remove()
    }

    fun lockButtons(){
        if (flipped){
            leftBar.lockButtons()
            rightBar.lockButtons()
        } else {
            bottomBar.lockButtons()
            topBar.lockButtons()
        }
    }

    fun unlockButtons(){
        if (flipped){
            leftBar.unlockButtons()
            rightBar.unlockButtons()
        } else {
            topBar.unlockButtons()
            bottomBar.unlockButtons()
        }
    }


    fun rotate(){
        setWidthHeightValues()
        flipped = if (flipped) {
            removeLeftRight()
            topDownMode()
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean("isFlipped", false).apply()
            false
        } else {
            removeTopBottom()
            leftRightMode()
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean("isFlipped", true).apply()
            handleLeftRightBarCutoutAdjustment()
            true
        }
    }

    private fun checkIfValidNumber(num: Int) : Boolean{
        return num > 60
    }

    fun showButtons() {
        if (flipped){
            showLeftRightButtons()
            hideLeftRightButtons()
        } else {
            showTopBottomButtons()
            hideTopBottomButtons()
        }
    }

    fun showLeftRightButtons(){
        leftBar.showButtons()
        rightBar.showButtons()
    }

    fun hideLeftRightButtons(){
        leftBar.hideButtons()
        rightBar.hideButtons()
    }

    fun showTopBottomButtons(){
        topBar.showButtons()
        bottomBar.showButtons()
    }

    fun hideTopBottomButtons(){
        topBar.hideButtons()
        bottomBar.hideButtons()
    }
}