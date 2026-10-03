package com.okanetsolutions.briareus.quest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.core.net.toUri
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkGrabMovementType
import com.meta.spatial.isdk.IsdkGrabState
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.isdk.IsdkPanelResize
import com.meta.spatial.isdk.ResizeMode
import com.meta.spatial.isdk.createIsdkComponents
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.vr.VRFeature
import com.okanetsolutions.briareus.core.SpaceLayout
import com.okanetsolutions.briareus.core.SpacePanel
import com.okanetsolutions.briareus.core.Surroundings
import com.okanetsolutions.briareus.quest.ui.BriareusTheme
import com.okanetsolutions.briareus.quest.ui.ConversationScreen
import com.okanetsolutions.briareus.quest.ui.DiffScreen
import com.okanetsolutions.briareus.quest.ui.HomeScreen
import com.okanetsolutions.briareus.quest.ui.PairingScreen
import com.okanetsolutions.briareus.quest.ui.PanelFrame
import com.okanetsolutions.briareus.quest.ui.PageScreen
import com.okanetsolutions.briareus.quest.ui.PairFirst
import com.okanetsolutions.briareus.quest.ui.panelTitle
import com.okanetsolutions.briareus.quest.ui.Pane
import com.okanetsolutions.briareus.quest.ui.PullScreen
import com.okanetsolutions.briareus.quest.ui.PullsScreen
import com.okanetsolutions.briareus.quest.ui.StatusScreen
import com.okanetsolutions.briareus.quest.ui.VoiceScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The app: one immersive space with every panel floating around the user, in their room through the cameras or in the
 * virtual surroundings. It draws [Navigator.space] (which the voice and the panels' own buttons change) as panel
 * entities, and reads back where the user moved a panel by hand. The events stream stays open while it is shown.
 */
class MainActivity : AppSystemActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var release: (() -> Unit)? = null
    private val navigator get() = app.navigator

    private val entities = HashMap<String, Entity>()
    /** Where each panel was last put, to tell a hand's move from ours. */
    private val placed = HashMap<String, Pose>()
    private val registered = HashMap<String, Int>()
    private var skybox: Entity? = null
    /** Around the user's head when the panels were placed; null until tracking starts, and again to recentre. */
    private var room: Room? = null
    private var ticks = 0L
    private var startVoiceAfterPermission = false

    /**
     * The Spatial SDK's activity is a plain Activity, so the screens' permission requests and the file picker
     * (`rememberLauncherForActivityResult`) are carried out here, on the activity's own callbacks.
     */
    private val results = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            contract.getSynchronousResult(this@MainActivity, input)?.let { dispatchResult(requestCode, it.value); return }
            val intent = contract.createIntent(this@MainActivity, input)
            if (intent.action == RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS) {
                requestPermissions(intent.getStringArrayExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS).orEmpty(), requestCode)
            } else {
                @Suppress("DEPRECATION") // A plain Activity has nothing newer.
                startActivityForResult(intent, requestCode, options?.toBundle())
            }
        }
    }
    private val resultsOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = results }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!results.dispatchResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())

    override fun registerPanels(): List<PanelRegistration> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        release = store.watch()
    }

    override fun onStop() {
        release?.invoke()
        release = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** A notification's tap: bring its panel to the front. */
    private fun handle(intent: Intent?) {
        when (intent?.getStringExtra(Windows.EXTRA_PANEL)) {
            Windows.PANEL_CONVERSATION -> (intent.getStringExtra(Windows.EXTRA_SESSION) ?: intent.data?.lastPathSegment)
                ?.takeIf { store.connection.value != null }?.let { navigator.open(SpacePanel.Conversation(it)) }
            Windows.PANEL_STATUS -> navigator.open(SpacePanel.Status)
            Windows.PANEL_VOICE -> navigator.open(SpacePanel.Voice)
        }
    }

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        scene.setLightingEnvironment(Vector3(0.4f), Vector3(1.0f), -Vector3(1.0f, 3.0f, -2.0f), 0.3f)
        skybox = Entity.create(
            listOf(
                Mesh("mesh://skybox".toUri(), MeshCollision.NoCollision),
                Material().apply { baseTextureAndroidResourceId = R.drawable.skybox; unlit = true },
                Transform(Pose(Vector3(0f))),
                Visible(false),
            ),
        )
        scope.launch { navigator.surroundings.collect(::surround) }
        scope.launch { navigator.space.collect { if (room != null) draw(it) } }
        scope.launch { navigator.recenter.collect { if (it > 0) room = null } }
        scope.launch {
            store.connection.collect { connected ->
                if (connected == null) return@collect
                // Connected: ask once to notify, and keep alerts going while you are in other apps.
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                }
                EventsService.start(this@MainActivity)
            }
        }
    }

    override fun onSceneTick() {
        super.onSceneTick()
        val head = scene.getViewerPose()
        // The head pose is zero until tracking starts; place the panels on the first real one, and again on a recentre.
        if (room == null && head.t.y > MIN_HEAD_HEIGHT) {
            room = Room(head)
            placed.clear()
            draw(navigator.space.value)
        }
        if (++ticks % GRAB_CHECK_TICKS == 0L) {
            readBackMoves()
            room?.let { navigator.space.value = navigator.space.value.facing(it.yaw(head.forward())) }
        }
    }

    private fun surround(s: Surroundings) {
        scene.enablePassthrough(s == Surroundings.PASSTHROUGH)
        skybox?.setComponent(Visible(s == Surroundings.VIRTUAL))
    }

    // MARK: - Panels as entities

    /** Makes the entities match [layout]: new panels created, moved ones placed again, closed ones destroyed. */
    private fun draw(layout: SpaceLayout) {
        val keys = layout.panels.map { it.first.key }.toSet()
        entities.keys.filter { it !in keys }.forEach { key -> entities.remove(key)?.destroy(); placed.remove(key) }
        layout.panels.forEach { (panel, at) ->
            val pose = room?.pose(at) ?: return
            val entity = entities.getOrPut(panel.key) {
                Entity.create(
                    listOf(
                        Panel(registration(panel)), Transform(pose), Scale(Vector3(at.scale.toFloat())),
                        // Moved by its grab handles and resized from its corners; the resize adds the handles once the panel has its size.
                        IsdkGrabbable(enabled = true, movementType = IsdkGrabMovementType.Billboard),
                        IsdkPanelResize(enabled = true, resizeMode = ResizeMode.Relayout, minDimensions = Vector2(0.4f, 0.3f), maxDimensions = Vector2(3.0f, 2.0f)),
                    ),
                ).also(::makeHittable)
            }
            if (placed[panel.key] != pose) {
                entity.setComponent(Transform(pose))
                entity.setComponent(Scale(Vector3(at.scale.toFloat())))
                placed[panel.key] = pose
            }
        }
    }

    /**
     * Gives a panel its pointer and hand input. The input system sizes a panel's hit area in the frame the panel appears,
     * before a panel registered at runtime has its scene object, so it is done again once the scene object exists.
     */
    private fun makeHittable(entity: Entity) {
        systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity)?.thenAccept { sceneObject ->
            runOnUiThread { if (entities.containsValue(entity)) (sceneObject as? PanelSceneObject)?.createIsdkComponents(entity) }
        }
    }

    /**
     * A panel the user carried somewhere with the grip: once let go, keep its new place in the layout, so the next spoken
     * move starts there. It is not placed again, so it stays exactly where it was let go.
     */
    private fun readBackMoves() {
        val room = room ?: return
        var layout = navigator.space.value
        entities.forEach { (key, entity) ->
            if (entity.tryGetComponent<IsdkGrabbable>()?.grabState == IsdkGrabState.Grabbed) return@forEach
            val now = entity.getComponent<Transform>().transform
            val was = placed[key] ?: return@forEach
            if (now.t.distanceTo(was.t) < MOVED_METRES) return@forEach
            val at = layout[key]?.second ?: return@forEach
            val place = room.placement(now.t, at)
            layout = layout.moved(key, place)
            placed[key] = room.pose(place)
        }
        if (layout != navigator.space.value) navigator.space.value = layout
    }

    /** The title bar dragged [by] dp from where it was taken hold of: the panel follows, from where it shows now. */
    private fun drag(key: String, by: Offset) {
        val room = room ?: return
        val now = entities[key]?.getComponent<Transform>()?.transform ?: return
        val layout = navigator.space.value
        val at = layout[key]?.second ?: return
        val metres = at.scale / DP_PER_METRE
        navigator.space.value = layout.moved(key, room.placement(now.t, at).dragged(by.x * metres, by.y * metres))
    }

    /** One panel registration per key, made the first time the panel is shown: its content follows the layout's current panel for that key. */
    private fun registration(panel: SpacePanel): Int = registered.getOrPut(panel.key) {
        val id = View.generateViewId()
        val (width, height) = size(panel)
        registerPanel(
            ComposeViewPanelRegistration(
                id,
                composeViewCreator = { _, context -> ComposeView(context).apply { setContent { PanelContent(panel.key) } } },
                settingsCreator = {
                    UIPanelSettings(
                        shape = QuadShapeOptions(width = width, height = height),
                        style = PanelStyleOptions(themeResourceId = R.style.Theme_Briareus_Panel),
                        display = DpPerMeterDisplayOptions(dpPerMeter = DP_PER_METRE),
                    )
                },
            ),
        )
        id
    }

    @Composable
    private fun PanelContent(key: String) = CompositionLocalProvider(
        LocalActivityResultRegistryOwner provides resultsOwner,
        // Every link opens in a page panel: the browser would end the space.
        LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) = navigator.openUrl(uri) },
    ) { BriareusTheme { PanelBody(key) } }

    @Composable
    private fun PanelBody(key: String) {
        val layout by navigator.space.collectAsState()
        val connection by store.connection.collectAsState()
        val panel = layout[key]?.first ?: return
        val front = layout.front
        val inFront = front?.key == key || (front == null && panel == SpacePanel.Main)
        val close = if (panel == SpacePanel.Main) null else ({ navigator.close(key) })
        PanelFrame(store, panelTitle(store, panel), inFront, onFront = { navigator.open(panel) }, onClose = close, onDrag = { drag(key, it) }) {
            if (connection == null && panel != SpacePanel.Main) { PairFirst(); return@PanelFrame }
            when (panel) {
                SpacePanel.Main -> if (connection == null) PairingScreen(store) else Home()
                SpacePanel.Status -> StatusScreen(store) { id -> navigator.open(SpacePanel.Conversation(id)) }
                SpacePanel.Voice -> VoiceScreen(app.voice, app.voiceSettings, ::startVoice)
                is SpacePanel.Conversation -> ConversationScreen(store, panel.sessionId, onBack = null, onPopOut = null, onDeleted = { navigator.close(key) })
                is SpacePanel.Pulls -> PullsScreen(store, panel.repo, ::route, back = null)
                is SpacePanel.Pull -> PullScreen(store, panel.repo, panel.number, ::route) { navigator.open(SpacePanel.Diff(panel.repo, panel.number)) }
                is SpacePanel.Diff -> DiffScreen(store, panel.repo, panel.number, panel.file)
                is SpacePanel.Preview -> key(panel.url) { PageScreen(panel.url, navigator::openUrl) }
                is SpacePanel.Web -> key(panel.url) { PageScreen(panel.url, navigator::openUrl) }
            }
        }
    }

    /** What a screen asks to show: a pull request, a project's pull requests or a conversation gets a screen of its own; the rest goes to the main window. */
    private fun route(pane: Pane?) {
        when (pane) {
            is Pane.Pull -> navigator.open(SpacePanel.Pull(pane.repo, pane.number))
            is Pane.Pulls -> navigator.open(SpacePanel.Pulls(pane.repo))
            is Pane.Open -> navigator.open(SpacePanel.Conversation(pane.sessionId))
            else -> { navigator.pane.value = pane; navigator.open(SpacePanel.Main) }
        }
    }

    @Composable
    private fun Home() {
        val pane by navigator.pane.collectAsState()
        val voicePhase by app.voice.phase.collectAsState()
        val surroundings by navigator.surroundings.collectAsState()
        HomeScreen(
            // Conversations and the project's list stay in the main window, as the dashboard has them; a pull request gets a screen of its own.
            store, pane, onPane = { if (it is Pane.Pull) route(it) else navigator.pane.value = it },
            onPopOut = { id -> navigator.open(SpacePanel.Conversation(id)); navigator.pane.value = null },
            onStatusWindow = { navigator.open(SpacePanel.Status) },
            voiceOn = voicePhase != VoiceSession.Phase.OFF,
            onVoiceWindow = { navigator.open(SpacePanel.Voice) },
            virtual = surroundings == Surroundings.VIRTUAL,
            onSurroundings = { navigator.surroundings.value = if (surroundings == Surroundings.VIRTUAL) Surroundings.PASSTHROUGH else Surroundings.VIRTUAL },
            onRecenter = { navigator.space.value = navigator.space.value.reset(); navigator.recenter.value++ },
            onBackgroundAlerts = { on ->
                store.backgroundAlerts = on
                if (on) EventsService.start(this) else EventsService.stop(this)
            },
        )
    }

    // MARK: - The microphone

    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) app.voice.start()
        else { startVoiceAfterPermission = true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val answer = Intent().putExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS, permissions)
            .putExtra(RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, grantResults)
        if (results.dispatchResult(requestCode, RESULT_OK, answer)) return
        if (requestCode != REQUEST_MICROPHONE) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted && startVoiceAfterPermission) app.voice.start()
        else if (!granted) store.say("Briareus needs the microphone to talk with you.")
        startVoiceAfterPermission = false
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
        const val REQUEST_MICROPHONE = 2
        /** The panels' density: 1280 dp, the main window's width, is 1.6 metres. */
        const val DP_PER_METRE = 800f
        const val MIN_HEAD_HEIGHT = 0.3f
        const val MOVED_METRES = 0.02f
        /** About four times a second at the headset's frame rate. */
        const val GRAB_CHECK_TICKS = 18L

        /** Each kind of panel's size in metres: the old windows' sizes in dp, at [DP_PER_METRE]. */
        fun size(panel: SpacePanel): Pair<Float, Float> = when (panel) {
            SpacePanel.Main -> 1.6f to 1.0f
            SpacePanel.Status -> 0.5f to 0.8f
            SpacePanel.Voice -> 0.575f to 0.9f
            is SpacePanel.Conversation -> 0.95f to 1.125f
            is SpacePanel.Pulls -> 1.0f to 1.0f
            is SpacePanel.Pull -> 1.2f to 1.0f
            is SpacePanel.Diff -> 1.4f to 1.0f
            is SpacePanel.Preview, is SpacePanel.Web -> 1.2f to 0.9f
        }
    }
}
