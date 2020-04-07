package de.wiomoc.tv

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.Format.NO_VALUE
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.extractor.ExtractorsFactory
import com.google.android.exoplayer2.extractor.ts.TsExtractor
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import com.google.android.exoplayer2.video.VideoListener
import com.google.android.material.snackbar.Snackbar
import de.wiomoc.tv.service.formatTime
import de.wiomoc.tv.service.getFriendlyDay
import kotlinx.android.synthetic.main.activity_player.*
import java.util.*


class PlayerActivity : AppCompatActivity() {

    class EPGViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val eventNameTextView: TextView = view.findViewById(R.id.epg_event_name)
        private val eventTimeTextView: TextView = view.findViewById(R.id.epg_event_time)
        private val eventDayTextView: TextView = view.findViewById(R.id.epg_event_day)
        private val eventDescriptionTextView: TextView = view.findViewById(R.id.epg_event_description)

        fun applyEvent(event: EPGEvent) {
            eventTimeTextView.text = formatTime(event.time)
            eventDayTextView.text = getFriendlyDay(event.time)
            eventNameTextView.text = event.name
            eventDescriptionTextView.text = (event.description ?: event.shortDescription)
        }
    }

    inner class EPGRecyclerViewAdapter : RecyclerView.Adapter<EPGViewHolder>(), EITReader.EPGEventListener {
        private val events = mutableListOf<EPGEvent>()
        var nextEventCountDownHandler = Handler(Looper.getMainLooper())

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            EPGViewHolder(LayoutInflater.from(this@PlayerActivity).inflate(R.layout.item_epg_event, parent, false))

        override fun getItemCount() = events.size

        override fun onBindViewHolder(holder: EPGViewHolder, position: Int) = holder.applyEvent(events[position])

        override fun onNewEvent(event: EPGEvent) {
            runOnUiThread {
                if (!event.isInPast) {
                    // Binary Search
                    var left = 0
                    var right = events.size
                    while (left < right) {
                        val middle = (right + left) / 2
                        if (events[middle].time < event.time) {
                            left = middle + 1
                        } else {
                            right = middle
                        }
                    }
                    if (left == 0 && events.isNotEmpty()) {
                        scheduleNextEventUpdate(events[0])
                    } else if (left == 1) {
                        scheduleNextEventUpdate(event)
                    }
                    events.add(left, event)
                    notifyItemInserted(left)
                    if (event.isCurrently)
                        main_player_controls.currentEvent = event
                    // TODO Refactor
                    epg_list.layoutManager?.smoothScrollToPosition(epg_list, RecyclerView.State(), 0)
                }
            }
        }

        private fun scheduleNextEventUpdate(nextEvent: EPGEvent) {
            println("NEXT" + nextEvent.time)
            val messageId = 42
            nextEventCountDownHandler.removeCallbacksAndMessages(messageId)
            val offset = nextEvent.time.time - Date().time
            nextEventCountDownHandler.postDelayed({
                events.removeAt(0)
                notifyItemRemoved(0)
                main_player_controls.currentEvent = events[0]
                if (events.size > 1)
                    scheduleNextEventUpdate(events[1])
            }, messageId, offset)
        }
    }

    lateinit var player: SimpleExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("name")!!
        val url = intent.getStringExtra("url")!!
        setContentView(R.layout.activity_player)
        player_progress.visibility = View.VISIBLE

        supportActionBar!!.apply {
            title = name
            setDisplayHomeAsUpEnabled(true)
        }

        val rendererFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        player = SimpleExoPlayer.Builder(this, rendererFactory).build();
        val dataSourceFactory = DefaultDataSourceFactory(
            this,
            Util.getUserAgent(this, "selfnet.tv,android")
        );

        enableFullscreenOnLandscape()

        val epgAdapter = EPGRecyclerViewAdapter()
        epg_list.adapter = epgAdapter

        val videoSource =
            ProgressiveMediaSource.Factory(dataSourceFactory, ExtractorsFactory {
                arrayOf(TsExtractor().withEPGListener(epgAdapter))
            })
                .createMediaSource(Uri.parse(url));

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_MOVIE)
            .build();
        player.audioAttributes = audioAttributes
        player.playWhenReady = true
        player.volume = 1.0f
        player.prepare(videoSource);

        player.addListener(object : Player.EventListener {
            var snackbar: Snackbar? = null
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        player_progress.visibility = View.GONE
                        snackbar?.dismiss()
                    }
                    Player.STATE_BUFFERING -> player_progress.visibility = View.VISIBLE
                    else -> {
                    }
                }
            }

            override fun onPlayerError(error: ExoPlaybackException) {
                snackbar =
                    Snackbar.make(findViewById(android.R.id.content), error.message!!, Snackbar.LENGTH_INDEFINITE)
                        .apply { show() }
            }

            override fun onTracksChanged(trackGroups: TrackGroupArray, trackSelections: TrackSelectionArray) {
                val isRadio =
                    trackSelections.all.all { it?.selectedFormat?.channelCount != NO_VALUE } // Only audio channels -> radio
                if (isRadio) {
                    main_player_radio_only.visibility = View.VISIBLE
                    main_player_surface.visibility = View.GONE
                    main_player_container.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                }
            }
        })

        // Default aspect ration 16:9
        main_player_container.setAspectRatio(16f / 9f)
        player.addVideoListener(object : VideoListener {
            override fun onVideoSizeChanged(
                width: Int,
                height: Int,
                unappliedRotationDegrees: Int,
                pixelWidthHeightRatio: Float
            ) {
                main_player_container.setAspectRatio((width * pixelWidthHeightRatio) / height)
            }
        })

        player.videoComponent!!.setVideoSurfaceView(main_player_surface)
        main_player_surface.keepScreenOn = true
        // main_player.setErrorMessageProvider { Pair.create(1, "Something went wrong :(") }
        // val debugView = TextView(this)
        // main_player.overlayFrameLayout!!.addView(debugView)
        // DebugTextViewHelper(player, debugView).start()

        main_player_controls.setOnExitFullscreenListener {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        main_player_surface.setOnTouchListener { view, motionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                main_player_controls.toggleVisibility()
                true
            } else
                false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_player, menu)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            menu.findItem(R.id.enter_pip).isVisible = false
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.enter_fullscreen -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            R.id.enter_pip -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                enterPictureInPictureMode()
                epg_list.visibility = View.GONE
            }
            android.R.id.home -> finish()
            else -> return false
        }
        return true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableFullscreenOnLandscape(newConfig)
    }

    private fun enableFullscreenOnLandscape(configuration: Configuration = resources.configuration) {
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            epg_list.visibility = View.GONE
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
            supportActionBar!!.hide()
        } else {
            epg_list.visibility = View.VISIBLE
            window.decorView.systemUiVisibility = 0
            supportActionBar!!.show()
        }
    }
}
