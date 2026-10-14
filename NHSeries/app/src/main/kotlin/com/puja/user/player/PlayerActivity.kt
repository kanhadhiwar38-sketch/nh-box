package com.puja.user.player

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.puja.user.R
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_VIDEO_POSITION = "extra_video_position"
        const val EXTRA_PLAYLIST = "extra_playlist"
        const val EXTRA_PLAYLIST_TITLES = "extra_playlist_titles"

        /** Optional: caps playback at N milliseconds (used for the VIP 2-minute free preview). */
        const val EXTRA_PREVIEW_LIMIT_MS = "extra_preview_limit_ms"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    private var playlistUris: Array<String> = emptyArray()
    private var playlistTitles: Array<String> = emptyArray()
    private var currentIndex = 0

    private lateinit var audioManager: AudioManager
    private var maxVolume = 0

    private val handler = Handler(Looper.getMainLooper())
    private var isControlsVisible = true
    private val hideControlsRunnable = Runnable { hideControls() }

    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnAudioTrack: ImageButton
    private lateinit var btnLock: ImageButton
    private lateinit var btnPip: ImageButton
    private lateinit var btnSpeed: ImageButton
    private lateinit var btnAspect: ImageButton
    private lateinit var btnRotateTop: ImageButton
    private lateinit var btnRotateHint: ImageButton
    private lateinit var layoutRotateHint: View
    private lateinit var progressBuffering: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var tvGesture: TextView
    private lateinit var tvPreviewBadge: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var unlockHintOverlay: View

    private var isLocked = false
    private var lockTouchStartX = 0f  // for swipe-to-show-unlock detection
    private var playbackSpeed = 1.0f
    private val speedOptions = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    private var aspectRatioIndex = 0
    private val aspectLabels = arrayOf("Fit", "Fill", "Zoom", "16:9", "4:3")

    private lateinit var gestureDetector: GestureDetector
    private var initialGestureX = 0f
    private var initialGestureY = 0f
    private var gestureStartVolume = 0
    private var gestureStartBrightness = 0f
    private var isVolumeGesture = false
    private var isBrightnessGesture = false

    // VIP free-preview countdown (e.g. 2 minutes). 0 means no cap on this video.
    private var previewLimitMs: Long = 0L
    private var previewTimer: CountDownTimer? = null
    private var previewEnded = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player?.pause()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player_nh)
        hideSystemUI()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        playlistUris = intent.getStringArrayExtra(EXTRA_PLAYLIST) ?: emptyArray()
        playlistTitles = intent.getStringArrayExtra(EXTRA_PLAYLIST_TITLES) ?: emptyArray()
        currentIndex = intent.getIntExtra(EXTRA_VIDEO_POSITION, 0)
        previewLimitMs = intent.getLongExtra(EXTRA_PREVIEW_LIMIT_MS, 0L)

        if (playlistUris.isEmpty()) {
            val uri = intent.getStringExtra(EXTRA_VIDEO_URI)
            val title = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Video"
            if (uri != null) {
                playlistUris = arrayOf(uri)
                playlistTitles = arrayOf(title)
            }
        }

        if (playlistUris.isEmpty()) {
            Toast.makeText(this, "No video link available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        playerView = findViewById(R.id.player_view_nh)
        setupCustomControls()
        setupGestures()
        initPlayer()
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        if (previewLimitMs > 0) startPreviewCountdown()
    }

    private fun setupCustomControls() {
        btnPlayPause     = findViewById(R.id.btn_play_pause_nh)
        btnPrev          = findViewById(R.id.btn_prev_nh)
        btnNext          = findViewById(R.id.btn_next_nh)
        btnAudioTrack    = findViewById(R.id.btn_audio_track_nh)
        btnLock          = findViewById(R.id.btn_lock_nh)
        btnPip           = findViewById(R.id.btn_pip_nh)
        btnSpeed         = findViewById(R.id.btn_speed_nh)
        btnAspect        = findViewById(R.id.btn_aspect_nh)
        btnRotateTop     = findViewById(R.id.btn_rotate_top_nh)
        btnRotateHint    = findViewById(R.id.btn_rotate_nh)
        layoutRotateHint = findViewById(R.id.layout_rotate_hint_nh)
        progressBuffering = findViewById(R.id.progress_buffering_nh)
        tvTitle          = findViewById(R.id.tv_player_title_nh)
        tvCurrentTime    = findViewById(R.id.tv_current_time_nh)
        tvTotalTime      = findViewById(R.id.tv_total_time_nh)
        tvGesture        = findViewById(R.id.tv_gesture_indicator_nh)
        tvPreviewBadge   = findViewById(R.id.tv_preview_badge_nh)
        seekBar          = findViewById(R.id.seekbar_progress_nh)
        unlockHintOverlay = findViewById(R.id.unlock_hint_overlay_nh)

        btnPlayPause.setOnClickListener  { togglePlayPause() }
        btnPrev.setOnClickListener       { playPrevious() }
        btnNext.setOnClickListener       { playNext() }
        btnAudioTrack.setOnClickListener { showAudioTrackSelector() }
        btnLock.setOnClickListener       { toggleLock() }
        btnSpeed.setOnClickListener      { showSpeedSelector() }
        btnAspect.setOnClickListener     { cycleAspectRatio() }
        btnRotateTop.setOnClickListener  { toggleOrientation() }
        btnRotateHint.setOnClickListener { toggleOrientation(); layoutRotateHint.visibility = View.GONE }
        // Tapping the centered unlock overlay unlocks the screen
        unlockHintOverlay.setOnClickListener { if (isLocked) toggleLock() }
        findViewById<ImageButton>(R.id.btn_back_player_nh)
            .setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            btnPip.setOnClickListener { enterPipMode() }
            btnPip.visibility = View.VISIBLE
        } else {
            btnPip.visibility = View.GONE
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = effectiveDurationMs()
                    tvCurrentTime.text = formatTime(duration * progress / 1000L)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                handler.removeCallbacks(hideControlsRunnable)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                val duration = effectiveDurationMs()
                player?.seekTo(duration * sb.progress / 1000L)
                scheduleHideControls()
            }
        })

        playerView.setOnClickListener { toggleControls() }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val width = playerView.width
                when {
                    e.x < width / 3f       -> { seekRelative(-10_000); showGestureHint("-10s") }
                    e.x > width * 2f / 3f  -> { seekRelative(10_000);  showGestureHint("+10s") }
                    else                    -> togglePlayPause()
                }
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            if (isLocked) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lockTouchStartX = event.x
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - lockTouchStartX
                        val swipeThreshold = playerView.width * 0.12f  // 12% of screen width
                        // Show unlock overlay on: tap, left swipe, or right swipe
                        if (abs(dx) < 40f || abs(dx) >= swipeThreshold) {
                            showUnlockOverlay()
                        }
                    }
                }
                return@setOnTouchListener true
            }
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialGestureX = event.x
                    initialGestureY = event.y
                    gestureStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    gestureStartBrightness = window.attributes.screenBrightness
                        .let { if (it < 0) 0.5f else it }
                    isVolumeGesture = false
                    isBrightnessGesture = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - initialGestureX
                    val dy = event.y - initialGestureY
                    if (!isVolumeGesture && !isBrightnessGesture
                        && abs(dy) > 30 && abs(dy) > abs(dx)) {
                        if (initialGestureX > playerView.width / 2f) isVolumeGesture = true
                        else isBrightnessGesture = true
                    }
                    if (isVolumeGesture) {
                        val delta = -(dy / playerView.height * maxVolume * 2).toInt()
                        val newVol = (gestureStartVolume + delta).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        showGestureHint("\uD83D\uDD0A ${newVol * 100 / maxVolume}%")
                    } else if (isBrightnessGesture) {
                        val newBright = (gestureStartBrightness - dy / playerView.height)
                            .coerceIn(0.01f, 1.0f)
                        window.attributes = window.attributes.also { it.screenBrightness = newBright }
                        showGestureHint("\u2600 ${(newBright * 100).toInt()}%")
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isVolumeGesture = false
                    isBrightnessGesture = false
                    handler.postDelayed({ tvGesture.visibility = View.GONE }, 1200)
                }
            }
            false
        }
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(), true
            )
            val mediaItems = playlistUris.mapIndexed { i, uriStr ->
                MediaItem.Builder().setUri(Uri.parse(uriStr)).setMediaId(i.toString()).build()
            }
            exo.setMediaItems(mediaItems, currentIndex, C.TIME_UNSET)

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    updatePlayPauseIcon()
                    progressBuffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_READY) {
                        updateDuration()
                        maybeShowRotateHint()
                    }
                    if (state == Player.STATE_ENDED && currentIndex < playlistUris.size - 1) playNext()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseIcon()
                    if (isPlaying) scheduleHideControls()
                    else handler.removeCallbacks(hideControlsRunnable)
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentIndex = exo.currentMediaItemIndex
                    updateTitle()
                }
                override fun onTracksChanged(tracks: Tracks) {
                    btnAudioTrack.alpha =
                        if (tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO } > 1) 1.0f else 0.4f
                }
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@PlayerActivity, "Playback error: ${error.errorCodeName}", Toast.LENGTH_SHORT).show()
                }
            })

            exo.prepare()
            exo.playWhenReady = true
            startProgressUpdater()
            updateTitle()
        }
    }

    private fun showAudioTrackSelector() {
        val exo = player ?: return
        val trackGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (trackGroups.isEmpty()) {
            Toast.makeText(this, "No alternate audio tracks", Toast.LENGTH_SHORT).show()
            return
        }
        val trackLabels = trackGroups.mapIndexed { i, group ->
            val format = group.getTrackFormat(0)
            val lang = format.language ?: "Track ${i + 1}"
            val channels = if (format.channelCount > 0) "${format.channelCount}ch" else ""
            val bitrate = if (format.bitrate > 0) "${format.bitrate / 1000}kbps" else ""
            listOf(lang, channels, bitrate).filter { it.isNotBlank() }.joinToString(" \u00B7 ")
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle("\uD83C\uDFA7 Audio Track")
            .setItems(trackLabels) { d, which ->
                val group = trackGroups[which]
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0)
                    ).build()
                d.dismiss()
                Toast.makeText(this, "Switched to: ${trackLabels[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun showSpeedSelector() {
        val labels = speedOptions.map { if (it == 1.0f) "Normal (1.0x)" else "${it}x" }.toTypedArray()
        val cur = speedOptions.indexOfFirst { it == playbackSpeed }.takeIf { it >= 0 } ?: 3
        AlertDialog.Builder(this)
            .setTitle("\u26A1 Playback Speed")
            .setSingleChoiceItems(labels, cur) { dialog, which ->
                playbackSpeed = speedOptions[which]
                player?.setPlaybackSpeed(playbackSpeed)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun cycleAspectRatio() {
        aspectRatioIndex = (aspectRatioIndex + 1) % aspectLabels.size
        playerView.resizeMode = when (aspectRatioIndex) {
            0 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            3 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            4 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        showGestureHint(aspectLabels[aspectRatioIndex])
    }

    /** Manual rotate button: flips the activity between landscape and portrait. */
    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    /** Shows a one-time "Rotate" hint if the video is widescreen but the device is still portrait. */
    private fun maybeShowRotateHint() {
        val exo = player ?: return
        val format = exo.videoFormat ?: return
        val isDeviceLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isVideoLandscape = format.width > format.height
        if (isVideoLandscape && !isDeviceLandscape) {
            layoutRotateHint.visibility = View.VISIBLE
            handler.postDelayed({ layoutRotateHint.visibility = View.GONE }, 4000)
        }
    }

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            // Lock: hide all controls including lock button
            handler.removeCallbacks(hideControlsRunnable)
            handler.removeCallbacks(hideLockIconRunnable)
            hideControls() // hides controls_container + btn_lock_nh
            btnLock.setImageResource(R.drawable.nh_ic_lock_closed)
            // Show centered unlock overlay briefly
            showUnlockOverlay()
            Toast.makeText(this, "Locked. Tap or swipe to unlock.", Toast.LENGTH_SHORT).show()
        } else {
            // Unlock: hide overlay, restore controls
            handler.removeCallbacks(hideLockIconRunnable)
            unlockHintOverlay.visibility = View.GONE
            btnLock.setImageResource(R.drawable.nh_ic_lock_open)
            showControls()
        }
    }

    /** Shows the centered unlock overlay and auto-hides it after 3s */
    private fun showUnlockOverlay() {
        unlockHintOverlay.visibility = View.VISIBLE
        scheduleHideLockIcon()
    }

    // Auto-hides the centered unlock overlay
    private val hideLockIconRunnable = Runnable {
        if (isLocked) unlockHintOverlay.visibility = View.GONE
    }

    private fun scheduleHideLockIcon() {
        handler.removeCallbacks(hideLockIconRunnable)
        handler.postDelayed(hideLockIconRunnable, 3000)
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    private fun togglePlayPause() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
        resetHideControlsTimer()
    }

    private fun playNext() {
        if (currentIndex < playlistUris.size - 1) player?.seekToNextMediaItem()
        else Toast.makeText(this, "No more videos", Toast.LENGTH_SHORT).show()
    }

    private fun playPrevious() {
        val exo = player ?: return
        if (exo.currentPosition > 3000) exo.seekTo(0)
        else if (currentIndex > 0) exo.seekToPreviousMediaItem()
    }

    private fun seekRelative(offsetMs: Long) {
        player?.let {
            it.seekTo((it.currentPosition + offsetMs).coerceIn(0, it.duration))
        }
    }

    private fun toggleControls() {
        if (isLocked) return  // touch listener handles locked state
        if (isControlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        if (isLocked) return
        isControlsVisible = true
        findViewById<View>(R.id.controls_container_nh).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_lock_nh).visibility           = View.VISIBLE
        scheduleHideControls()
    }

    private fun hideControls() {
        isControlsVisible = false
        findViewById<View>(R.id.controls_container_nh).visibility = View.GONE
        findViewById<View>(R.id.btn_lock_nh).visibility           = View.GONE
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3500)
    }

    private fun resetHideControlsTimer() { showControls(); scheduleHideControls() }

    private fun showLockedToast() =
        Toast.makeText(this, "Screen is locked", Toast.LENGTH_SHORT).show()

    private fun updatePlayPauseIcon() {
        btnPlayPause.setImageResource(
            if (player?.isPlaying == true) R.drawable.nh_ic_pause else R.drawable.nh_ic_play
        )
    }

    private fun updateTitle() {
        if (currentIndex < playlistTitles.size) tvTitle.text = playlistTitles[currentIndex]
    }

    /** Caps the reported duration to the preview limit so the seek bar reflects the free-preview window. */
    private fun effectiveDurationMs(): Long {
        val realDuration = player?.duration ?: 0L
        if (realDuration <= 0) return 0L
        return if (previewLimitMs > 0) minOf(realDuration, previewLimitMs) else realDuration
    }

    private fun updateDuration() {
        tvTotalTime.text = formatTime(effectiveDurationMs())
        seekBar.max = 1000
    }

    private fun startProgressUpdater() {
        handler.post(object : Runnable {
            override fun run() {
                val exo = player ?: return
                val pos = exo.currentPosition
                val dur = effectiveDurationMs()
                if (dur > 0) {
                    val clamped = pos.coerceAtMost(dur)
                    tvCurrentTime.text = formatTime(clamped)
                    seekBar.progress = (clamped * 1000 / dur).toInt()
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    /** Starts the VIP free-preview countdown; pauses playback and prompts an upgrade when it expires. */
    private fun startPreviewCountdown() {
        tvPreviewBadge.visibility = View.VISIBLE
        previewTimer = object : CountDownTimer(previewLimitMs, 1000) {
            override fun onTick(msLeft: Long) {
                val secs = (msLeft / 1000).toInt()
                tvPreviewBadge.text = "VIP Preview \u00B7 ${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
            }
            override fun onFinish() {
                if (previewEnded) return
                previewEnded = true
                player?.pause()
                tvPreviewBadge.text = "Preview Ended"
                Toast.makeText(this@PlayerActivity, "Preview ended. Unlock VIP to watch the full video.", Toast.LENGTH_LONG).show()
                handler.postDelayed({ finish() }, 1200)
            }
        }.start()
    }

    private fun showGestureHint(text: String) {
        tvGesture.text = text
        tvGesture.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages("gesture")
        handler.postAtTime(
            { tvGesture.visibility = View.GONE },
            "gesture",
            android.os.SystemClock.uptimeMillis() + 1500
        )
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutRotateHint.visibility = View.GONE
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        findViewById<View>(R.id.controls_container_nh).visibility =
            if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode)
            player?.pause()
    }

    override fun onStop() { super.onStop(); handler.removeCallbacksAndMessages(null) }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        previewTimer?.cancel()
        player?.release(); player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onBackPressed() {
        if (isLocked) { showLockedToast(); return }
        super.onBackPressed()
    }
}
