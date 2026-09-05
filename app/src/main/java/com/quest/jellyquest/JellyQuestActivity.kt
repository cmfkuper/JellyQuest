package com.quest.jellyquest

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.net.toUri
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.datamodelinspector.DataModelInspectorFeature
import com.meta.spatial.debugtools.HotReloadFeature
import com.meta.spatial.ovrmetrics.OVRMetricsDataModel
import com.meta.spatial.ovrmetrics.OVRMetricsFeature
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Material
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelSettings
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.core.Hand
import com.meta.spatial.core.Quaternion
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.spatialaudio.AudioSessionId
import com.meta.spatial.spatialaudio.AudioSessionStereoOffsets
import com.meta.spatial.spatialaudio.AudioType
import com.meta.spatial.spatialaudio.SpatialAudioFeature
import com.meta.spatial.vr.VRFeature
import com.quest.jellyquest.audio.AudioSettings
import com.quest.jellyquest.audio.RoomAcousticsController
import com.quest.jellyquest.streaming.AuthState
import com.quest.jellyquest.streaming.ExoPlayerSource
import com.quest.jellyquest.streaming.JellyfinClient
import com.quest.jellyquest.streaming.PlaybackReporter
import kotlinx.coroutines.launch

/**
 * Orchestrator for the VR theater experience. Owns entity lifecycle, event wiring,
 * and SDK panel registration. Delegates ALL positioning math to layout classes:
 *
 *  - [TheaterLayout] — world-anchored objects (screen, environment). Placed once
 *    in world space, fixed unless the anchor resets on recenter.
 *  - [ViewerLayout] — viewer-relative objects (browse panel). Positioned relative
 *    to the user's seated location, follows seat elevation changes.
 *
 * [Anchor] captures the user's position and facing direction at startup/recenter.
 * [TheaterState] is the single source of truth for screen config + riser height.
 *
 * See issues/002-theater-positioning-architecture.md for the full design rationale.
 */
class JellyQuestActivity : AppSystemActivity() {

  companion object {
    private const val TAG = "VirtualMonitor"
  }

  private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  val theaterState = mutableStateOf(TheaterState())

  private var screenEntity: Entity? = null
  private var subtitleEntity: Entity? = null
  private var browsePanelEntity: Entity? = null
  val browsePanelVisible = mutableStateOf(false)
  var browseHandleEntity: Entity? = null
    private set

  // Where the user last dragged the browse panel; overrides the default spawn
  // pose until a recenter invalidates world coordinates.
  private var customBrowsePose: Pose? = null
  private var controlsPanelEntity: Entity? = null
  val controlsPanelVisible = mutableStateOf(false)
  private var skyboxEntity: Entity? = null
  private var floorEntity: Entity? = null
  private var wallEntities: List<Entity> = emptyList()
  private var armrestEntities: List<Entity> = emptyList()
  private var environmentModelEntity: Entity? = null

  // Jellyfin + ExoPlayer
  lateinit var exoPlayerSource: ExoPlayerSource
  lateinit var jellyfinClient: JellyfinClient
  private lateinit var playbackReporter: PlaybackReporter

  // Audio: spatial positioning and room acoustics
  private val spatialAudioFeature = SpatialAudioFeature()
  private lateinit var audioSettings: AudioSettings
  private val roomAcousticsController = RoomAcousticsController()
  val spatialAudioEnabled = mutableStateOf(true)
  val roomAcousticsEnabled = mutableStateOf(true)

  // Current video dimensions for aspect-ratio fitting. Updated when ExoPlayer
  // reports a new video size; triggers screen respawn so the panel reshapes.
  private var videoWidth: Int = 0
  private var videoHeight: Int = 0

  // Anchor: immutable snapshot of the user's position and facing direction.
  // Captured at startup and on recenter. All placement is relative to this point.
  private var anchor: Anchor? = null

  // Ambient screen lighting ("bias lighting"): room surfaces and their base
  // colors, re-tinted in real time from the video's sampled average color.
  // Gain sets how strongly a surface reacts — fixtures glow (>2), surfaces
  // near the screen catch more light than the back of the house.
  private data class TintSurface(val entity: Entity, val base: Color4, val gain: Float)
  private val tintableEntities = mutableListOf<TintSurface>()
  private var ambientR = 0.5f
  private var ambientG = 0.5f
  private var ambientB = 0.5f
  private var appliedR = -1f
  private var appliedG = -1f
  private var appliedB = -1f
  private var lastTintApplyMs = 0L

  override fun registerFeatures(): List<SpatialFeature> {
    val features =
        mutableListOf<SpatialFeature>(
            VRFeature(this),
            ComposeFeature(),
            spatialAudioFeature,
        )
    if (BuildConfig.DEBUG) {
      features.add(CastInputForwardFeature(this))
      features.add(HotReloadFeature(this))
      features.add(OVRMetricsFeature(this, OVRMetricsDataModel() { numberOfMeshes() }))
      features.add(DataModelInspectorFeature(spatial, this.componentManager))
    }
    return features
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    exoPlayerSource = ExoPlayerSource(this)
    jellyfinClient = JellyfinClient(this)
    playbackReporter = PlaybackReporter(
        jellyfinClient = jellyfinClient,
        positionProvider = { exoPlayerSource.player.currentPosition },
        scope = activityScope,
    )
    Log.i(TAG, "ExoPlayer, Jellyfin client, and playback reporter initialized")

    // Audio settings
    audioSettings = AudioSettings(getSharedPreferences("audio_settings", MODE_PRIVATE))
    spatialAudioEnabled.value = audioSettings.spatialAudioEnabled
    roomAcousticsEnabled.value = audioSettings.roomAcousticsEnabled

    // Wire spatial audio and room acoustics to ExoPlayer's audio session
    exoPlayerSource.onPlayerReady = { wireSpatialAudio() }

    // Ambient lighting: sampled screen color arrives on the GL thread.
    exoPlayerSource.onScreenColor = { r, g, b ->
      runOnUiThread { applyAmbientColor(r, g, b) }
    }

    // Reshape screen panel when video dimensions change (aspect-ratio masking)
    activityScope.launch {
      exoPlayerSource.mediaInfo.collect { info ->
        val w = info?.width ?: 0
        val h = info?.height ?: 0
        if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
          videoWidth = w
          videoHeight = h
          Log.i(TAG, "Video size changed to ${w}x${h}, reshaping screen panel")
          respawnScreen()
        }
      }
    }

    // Pre-fetch library content if already authenticated from saved credentials
    if (jellyfinClient.authState.value == AuthState.AUTHENTICATED) {
      activityScope.launch { jellyfinClient.prefetchLibraryContent() }
    }

  }

  override fun onPause() {
    // Capture position and send stopped report so Jellyfin saves userData.
    // NonCancellable ensures the network call completes even if onDestroy cancels the scope.
    val positionMs = exoPlayerSource.player.currentPosition
    activityScope.launch {
      withContext(NonCancellable) { playbackReporter.stopReportingAtPosition(positionMs) }
    }
    super.onPause()
  }

  override fun onDestroy() {
    roomAcousticsController.disable()
    activityScope.cancel()
    exoPlayerSource.disconnect()
    exoPlayerSource.release()
    super.onDestroy()
  }

  override fun onSceneReady() {
    super.onSceneReady()
    Log.i(TAG, "onSceneReady called")

    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)

    scene.setLightingEnvironment(
        ambientColor = Vector3(8.0f),
        sunColor = Vector3(0.0f, 0.0f, 0.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.0f,
    )

    scene.setViewOrigin(0.0f, 0.0f, 0.0f)

    // Try to capture the anchor now; if head tracking isn't ready yet,
    // the AnchorCaptureSystem will keep trying each frame until it succeeds.
    if (!captureAnchor()) {
      systemManager.registerSystem(AnchorCaptureSystem(this))
    } else {
      spawnEnvironment()
      spawnScreen()
      startBumpers()
      // Auto-open browse panel if library cache is available
      if (jellyfinClient.cachedLibraries.value != null) {
        browsePanelVisible.value = true
        spawnBrowsePanel()
      }
    }

    systemManager.registerSystem(PanelDragSystem(this))

    systemManager.registerSystem(
        ControllerInputSystem(
            isBrowseVisible = { browsePanelVisible.value },
            onBrowseToggle = {
              Log.i(TAG, "onBrowseToggle: visible=${browsePanelVisible.value}")
              if (!browsePanelVisible.value) {
                browsePanelVisible.value = true
                spawnBrowsePanel()
              } else {
                dismissBrowsePanel()
              }
            },
            onControlsToggle = {
              if (!controlsPanelVisible.value) showControlsPanel() else dismissControlsPanel()
            },
            onPlayPauseToggle = { handlePlayPause() },
            onStop = { handleStop() },
            onSeekForward = { exoPlayerSource.seekForward() },
            onSeekBackward = { exoPlayerSource.seekBackward() },
        )
    )
  }

  // Seated-height calibration: the layout assumes eyes at SEATED_EYE_HEIGHT
  // above the theater floor, but the real headset may sit lower (couch,
  // recliner). Measure the real head height at anchor capture and lift the
  // view origin by the difference, so the viewer's eyes always land at
  // movie-seat height regardless of posture.
  private var viewOriginY = 0f
  private var eyeBoost = 0f

  fun captureAnchor(): Boolean {
    val captured = Anchor.capture() ?: return false
    anchor = captured
    // headHeight includes whatever origin offset was active at capture time.
    val realHeadHeight = captured.headHeight - viewOriginY
    eyeBoost = (ViewerLayout.SEATED_EYE_HEIGHT - realHeadHeight).coerceIn(-0.4f, 0.7f)
    Log.i(TAG, "Seated calibration: realHeadHeight=$realHeadHeight eyeBoost=$eyeBoost")
    applyViewOrigin()
    return true
  }

  /** Set the view origin from the current seat riser plus the eye-height boost. */
  private fun applyViewOrigin() {
    viewOriginY = theaterState.value.riserHeightM + eyeBoost
    scene.setViewOrigin(0.0f, viewOriginY, 0.0f)
  }

  fun spawnScreenFromSystem() {
    spawnEnvironment()
    spawnScreen()
    startBumpers()
    // Auto-open browse panel if library cache is available
    if (jellyfinClient.cachedLibraries.value != null) {
      browsePanelVisible.value = true
      spawnBrowsePanel()
    }
  }

  private fun startBumpers() {
    exoPlayerSource.playBumpers(this, listOf(
        R.raw.bumper_regal,
        R.raw.bumper_chilly_dilly,
        R.raw.bumper_snipe,
    ))
  }

  private fun spawnScreen() {
    val a = anchor ?: return
    val screen = theaterState.value.screen
    val pose = TheaterLayout.screenPose(a, screen)

    Log.i(TAG, "Spawning screen at pos=${pose.t} rot=${pose.q} dist=${screen.distanceM}m height=${screen.screenCenterY}m")
    screenEntity =
        Entity.createPanelEntity(
            R.id.screen_panel,
            Transform(pose),
        )
    // The screen wall cutout provides a natural masking border around the video.
    // Separate frame entities are not used because VideoSurfacePanelRegistration renders
    // as a compositor layer, which doesn't share depth testing with regular mesh entities.

    // Subtitle overlay floats in front of the frame's bottom band.
    subtitleEntity?.destroy()
    subtitleEntity =
        Entity.createPanelEntity(
            R.id.subtitle_panel,
            Transform(TheaterLayout.subtitlePose(a, screen)),
        )
  }

  private fun respawnScreen() {
    screenEntity?.destroy()
    screenEntity = null
    subtitleEntity?.destroy()
    subtitleEntity = null
    spawnScreen()
  }

  private fun spawnBrowsePanel() {
    val a = anchor ?: return
    Log.i(TAG, "spawnBrowsePanel: creating entity")
    browsePanelEntity?.destroy()

    val pose = customBrowsePose ?: ViewerLayout.browsePanelPose(a, theaterState.value.riserHeightM)
    browsePanelEntity =
        Entity.createPanelEntity(
            R.id.browse_panel,
            Transform(pose),
        )
    spawnBrowseHandle(pose)
  }

  /** Purple grab bar floating above the browse panel — PanelDragSystem's target. */
  private fun spawnBrowseHandle(panelPose: Pose) {
    browseHandleEntity?.destroy()
    browseHandleEntity = Entity.create(listOf(
        Box(
            Vector3(-ViewerLayout.HANDLE_HALF_WIDTH, -ViewerLayout.HANDLE_HALF_THICKNESS, -ViewerLayout.HANDLE_HALF_THICKNESS),
            Vector3(ViewerLayout.HANDLE_HALF_WIDTH, ViewerLayout.HANDLE_HALF_THICKNESS, ViewerLayout.HANDLE_HALF_THICKNESS),
        ),
        Mesh("mesh://box".toUri(), hittable = MeshCollision.NoCollision),
        Material().apply {
          baseColor = Color4(0.74f, 0.58f, 0.98f, 1f) // Dracula purple
          unlit = true
        },
        Transform(ViewerLayout.browseHandlePose(panelPose)),
    ))
  }

  /** Current world pose of the browse panel, for the drag system. */
  fun currentBrowsePanelPose(): Pose? =
      browsePanelEntity?.tryGetComponent<Transform>()?.transform

  /** Short vibration on the right controller. Best-effort — never throws. */
  fun hapticPulse(amplitude: Float, durationMs: Long) {
    try {
      spatial.applyHapticFeedback(Hand.RIGHT, amplitude, durationMs, 0.5f)
    } catch (e: Throwable) {
      Log.w(TAG, "Haptic pulse failed: ${e.message}")
    }
  }

  /** Move the browse panel (and its handle) to [position], re-facing the viewer. */
  fun moveBrowsePanel(position: Vector3) {
    val a = anchor ?: return
    val dx = position.x - a.position.x
    val dz = position.z - a.position.z
    val yawDeg = Math.toDegrees(Math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
    val pose = Pose(position, Quaternion(ViewerLayout.BROWSE_TILT_DEG, yawDeg, 0f))
    customBrowsePose = pose
    browsePanelEntity?.setComponent(Transform(pose))
    browseHandleEntity?.setComponent(Transform(ViewerLayout.browseHandlePose(pose)))
  }

  private fun dismissBrowsePanel() {
    Log.i(TAG, "dismissBrowsePanel: visible=${browsePanelVisible.value}, entity=${browsePanelEntity?.id}")
    browsePanelVisible.value = false
    browsePanelEntity?.destroy()
    browsePanelEntity = null
    browseHandleEntity?.destroy()
    browseHandleEntity = null
  }

  private fun showControlsPanel() {
    val a = anchor ?: return
    controlsPanelEntity?.destroy()
    controlsPanelVisible.value = true
    val pose = ViewerLayout.controlsPanelPose(a, theaterState.value.riserHeightM)
    controlsPanelEntity =
        Entity.createPanelEntity(
            R.id.controls_panel,
            Transform(pose),
        )
  }

  private fun dismissControlsPanel() {
    controlsPanelVisible.value = false
    controlsPanelEntity?.destroy()
    controlsPanelEntity = null
  }

  private fun handlePlayPause() {
    exoPlayerSource.togglePlayPause()
    activityScope.launch { playbackReporter.reportCurrentPosition() }
    // Pausing brings up the HUD; resuming clears it. These (plus the stick
    // click / Y toggle) are the ONLY ways the HUD appears — never on its own.
    if (!exoPlayerSource.isBumperPlaying) {
      if (!exoPlayerSource.player.playWhenReady) {
        showControlsPanel()
      } else {
        dismissControlsPanel()
      }
    }
  }

  private fun handleSeekTo(positionMs: Long) {
    exoPlayerSource.player.seekTo(positionMs)
    activityScope.launch { playbackReporter.reportCurrentPosition() }
  }

  private fun handleStop() {
    // Capture position before stopping player (stop resets position to 0)
    val positionMs = exoPlayerSource.player.currentPosition
    exoPlayerSource.stop()
    lastWiredAudioSessionId = 0
    roomAcousticsController.disable()
    activityScope.launch {
      playbackReporter.stopReportingAtPosition(positionMs)
    }
    dismissControlsPanel()
    resetAmbientColor()
    // Auto-show browse panel for next selection
    if (!browsePanelVisible.value) {
      browsePanelVisible.value = true
      spawnBrowsePanel()
    }
  }

  private fun currentExperience(): TheaterExperience? {
    return THEATER_EXPERIENCES.firstOrNull { it.name == theaterState.value.screen.label }
  }

  private fun spawnEnvironment() {
    val a = anchor ?: return
    skyboxEntity?.destroy()
    floorEntity?.destroy()
    wallEntities.forEach { it.destroy() }
    wallEntities = emptyList()
    armrestEntities.forEach { it.destroy() }
    armrestEntities = emptyList()
    environmentModelEntity?.destroy()
    environmentModelEntity = null
    tintableEntities.clear()

    val envPos = TheaterLayout.environmentPosition(a)
    val screen = theaterState.value.screen
    val room = theaterState.value.room
    val experience = currentExperience()

    // Skybox: near-black sphere centered on the user
    val skyboxColor = Color4(0.05f, 0.05f, 0.07f, 1f)
    skyboxEntity = Entity.create(listOf(
        Mesh("mesh://skybox".toUri(), hittable = MeshCollision.NoCollision),
        Material().apply {
          baseColor = skyboxColor
          unlit = true
        },
        Transform(Pose(envPos)),
    ))
    skyboxEntity?.let { tintableEntities.add(TintSurface(it, skyboxColor, 0.7f)) }

    val asset = experience?.environmentAsset
    if (asset != null) {
      // GLB environment — 3D model replaces procedural walls, floor, and ceiling.
      // Lit environments shade from scene ambient (driven by the screen's
      // sampled color); baked environments render unlit.
      val glbPose = TheaterLayout.glbEnvironmentPose(a, screen, experience.environmentLateralOffsetM)
      val meshComponent = if (experience.environmentLit) {
        Mesh(mesh = asset.toUri(), hittable = MeshCollision.NoCollision)
      } else {
        Mesh(
            mesh = asset.toUri(),
            hittable = MeshCollision.NoCollision,
            defaultShaderOverride = SceneMaterial.UNLIT_SHADER,
        )
      }
      environmentModelEntity = Entity.create(listOf(
          meshComponent,
          Transform(Pose(glbPose.t, glbPose.q)),
      ))
      // Baseline house light level for lit rooms (the huge default ambient is
      // sized for unlit content and would blow a lit model out).
      if (experience.environmentLit) {
        setSceneAmbient(0.9f, 0.9f, 0.95f)
      }
      Log.i(TAG, "GLB environment loaded: $asset pos=${glbPose.t} rot=${glbPose.q}")
      Log.i(TAG, "  Anchor pos=${a.position} fwd=${a.forward}")
      Log.i(TAG, "  Model origin at screen wall, extends -Z toward viewer (30m deep)")
      Log.i(TAG, "  Viewer should be ~${screen.distanceM}m from screen wall inside model")
    } else {
      // Procedural box environment — flat-colored walls, floor, and ceiling
      setSceneAmbient(8.0f, 8.0f, 8.0f)  // restore the unlit-content baseline
      spawnProceduralEnvironment(a, envPos, screen, room)
    }

    // Armrests: only for procedural environments (GLB model has its own)
    if (asset == null) {
      val c = TheaterEnvironment.colors
      val armrestBox = Box(
          Vector3(-ViewerLayout.ARMREST_WIDTH / 2f, -ViewerLayout.ARMREST_HEIGHT / 2f, -ViewerLayout.ARMREST_LENGTH / 2f),
          Vector3(ViewerLayout.ARMREST_WIDTH / 2f, ViewerLayout.ARMREST_HEIGHT / 2f, ViewerLayout.ARMREST_LENGTH / 2f),
      )
      armrestEntities = listOf(
          createBoxEntity(armrestBox, c.armrest, ViewerLayout.armrestPose(a, theaterState.value.riserHeightM, isLeft = true)),
          createBoxEntity(armrestBox, c.armrest, ViewerLayout.armrestPose(a, theaterState.value.riserHeightM, isLeft = false)),
      )
    }
  }

  private fun spawnProceduralEnvironment(a: Anchor, envPos: Vector3, screen: ScreenConfig, room: RoomGeometry) {
    // Floor: dark charcoal ground plane centered on the user
    val floorHalfW = room.widthBack / 2f
    val floorHalfD = room.depth / 2f
    val floorColor = Color4(0.08f, 0.08f, 0.08f, 1f)
    floorEntity = Entity.create(listOf(
        Box(Vector3(-floorHalfW, -0.005f, -floorHalfD), Vector3(floorHalfW, 0.005f, floorHalfD)),
        Mesh("mesh://box".toUri(), hittable = MeshCollision.NoCollision),
        Material().apply {
          baseColor = floorColor
          unlit = true
        },
        Transform(Pose(envPos)),
    ))
    floorEntity?.let { tintableEntities.add(TintSurface(it, floorColor, 1f)) }

    // Walls and ceiling
    val wallLength = TheaterLayout.wallLength(screen, room)
    val t = TheaterEnvironment.WALL_THICKNESS

    // Screen wall splits around the screen opening — left flank, right flank, and top strip
    val screenHalfW = screen.widthM / 2f
    val screenTop = screen.screenBottomM + screen.heightM
    val screenWallPose = TheaterLayout.screenWallPose(a, screen, room)

    val c = TheaterEnvironment.colors

    wallEntities = listOf(
        // Screen wall — left flank (extends along local X, thin along Z)
        createBoxEntity(
            Box(Vector3(-room.widthFront / 2f, 0f, -t / 2f), Vector3(-screenHalfW, room.ceilingHeight, t / 2f)),
            c.screenWall,
            screenWallPose,
        ),
        // Screen wall — right flank
        createBoxEntity(
            Box(Vector3(screenHalfW, 0f, -t / 2f), Vector3(room.widthFront / 2f, room.ceilingHeight, t / 2f)),
            c.screenWall,
            screenWallPose,
        ),
        // Screen wall — strip above screen
        createBoxEntity(
            Box(Vector3(-screenHalfW, screenTop, -t / 2f), Vector3(screenHalfW, room.ceilingHeight, t / 2f)),
            c.screenWall,
            screenWallPose,
        ),
        // Screen wall — strip below screen
        createBoxEntity(
            Box(Vector3(-screenHalfW, 0f, -t / 2f), Vector3(screenHalfW, screen.screenBottomM, t / 2f)),
            c.screenWall,
            screenWallPose,
        ),
        // Back wall (extends along local X, thin along Z)
        createBoxEntity(
            Box(Vector3(-room.widthBack / 2f, 0f, -t / 2f), Vector3(room.widthBack / 2f, room.ceilingHeight, t / 2f)),
            c.backWall,
            TheaterLayout.backWallPose(a, room),
        ),
        // Left wall — extends along local Z (front-to-back), thin along local X
        createBoxEntity(
            Box(Vector3(-t / 2f, 0f, -wallLength / 2f), Vector3(t / 2f, room.ceilingHeight, wallLength / 2f)),
            c.sideWall,
            TheaterLayout.leftWallPose(a, screen, room),
        ),
        // Right wall — extends along local Z (front-to-back), thin along local X
        createBoxEntity(
            Box(Vector3(-t / 2f, 0f, -wallLength / 2f), Vector3(t / 2f, room.ceilingHeight, wallLength / 2f)),
            c.sideWall,
            TheaterLayout.rightWallPose(a, screen, room),
        ),
        // Ceiling (extends along local X and Z, thin along Y)
        createBoxEntity(
            Box(Vector3(-room.widthBack / 2f, -t / 2f, -wallLength / 2f), Vector3(room.widthBack / 2f, t / 2f, wallLength / 2f)),
            c.ceiling,
            TheaterLayout.ceilingPose(a, screen, room),
        ),
    )

    // Theater furnishings: stadium seating, aisle lights, wall panels,
    // ceiling strips, stage apron — every piece joins the ambient light sim.
    val seatDistances = currentExperience()?.seats?.map { it.distanceM }
        ?: listOf(screen.distanceM)
    for (piece in TheaterDecor.build(a, screen, room, seatDistances)) {
      // Only fixtures and the stage join the live light sim — re-tinting every
      // seat row every tick overwhelmed the renderer and froze the headset.
      createBoxEntity(
          Box(piece.min, piece.max), piece.color, piece.pose, piece.gain,
          tintable = piece.gain >= 1.4f,
      )
    }
  }

  private fun createBoxEntity(
      box: Box,
      color: Color4,
      pose: Pose,
      tintGain: Float = 1f,
      tintable: Boolean = true,
  ): Entity {
    val entity = Entity.create(listOf(
        box,
        Mesh("mesh://box".toUri(), hittable = MeshCollision.NoCollision),
        Material().apply {
          baseColor = color
          unlit = true
        },
        Transform(Pose(pose.t, pose.q)),
    ))
    if (tintable) {
      tintableEntities.add(TintSurface(entity, color, tintGain))
    }
    return entity
  }

  private fun setSceneAmbient(r: Float, g: Float, b: Float) {
    scene.setLightingEnvironment(
        ambientColor = Vector3(r, g, b),
        sunColor = Vector3(0.0f, 0.0f, 0.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.0f,
    )
  }

  private fun litEnvironmentActive(): Boolean =
      environmentModelEntity != null && currentExperience()?.environmentLit == true

  /**
   * Smooth the sampled screen color and drive the room's lighting from it —
   * virtual "bias lighting" so a bright screen brightens the theater and a
   * dark scene lets it fall away. Lit GLB rooms take it as scene ambient
   * (one call, real shading); procedural rooms re-tint their unlit materials.
   */
  private fun applyAmbientColor(r: Float, g: Float, b: Float) {
    // Exponential smoothing keeps cuts from strobing the room. High enough
    // that the room visibly tracks the screen within a beat.
    ambientR += 0.55f * (r - ambientR)
    ambientG += 0.55f * (g - ambientG)
    ambientB += 0.55f * (b - ambientB)

    // Material writes are expensive for the renderer: cap the rate and skip
    // entirely when the color hasn't visibly changed.
    val now = android.os.SystemClock.uptimeMillis()
    if (now - lastTintApplyMs < 100) return
    val delta = Math.abs(ambientR - appliedR) + Math.abs(ambientG - appliedG) + Math.abs(ambientB - appliedB)
    if (delta < 0.015f) return
    lastTintApplyMs = now
    appliedR = ambientR
    appliedG = ambientG
    appliedB = ambientB

    if (litEnvironmentActive()) {
      setSceneAmbient(
          0.35f + 2.6f * ambientR,
          0.35f + 2.6f * ambientG,
          0.35f + 2.6f * ambientB,
      )
    }

    for ((entity, base, gain) in tintableEntities) {
      // A floor keeps the room from going pitch black; gain scales how hard
      // this particular surface reacts to the screen.
      entity.setComponent(Material().apply {
        baseColor = Color4(
            (base.red * (0.35f + 2.2f * gain * ambientR)).coerceAtMost(1f),
            (base.green * (0.35f + 2.2f * gain * ambientG)).coerceAtMost(1f),
            (base.blue * (0.35f + 2.2f * gain * ambientB)).coerceAtMost(1f),
            base.alpha,
        )
        unlit = true
      })
    }
  }

  /** Restore the room's authored colors (used when playback stops). */
  private fun resetAmbientColor() {
    ambientR = 0.5f
    ambientG = 0.5f
    ambientB = 0.5f
    if (litEnvironmentActive()) {
      setSceneAmbient(0.9f, 0.9f, 0.95f)
    }
    for ((entity, base, _) in tintableEntities) {
      entity.setComponent(Material().apply {
        baseColor = base
        unlit = true
      })
    }
  }

  private fun applyTheaterPreset(theater: TheaterExperience, seat: SeatPosition) {
    theaterState.value = TheaterState(
        screen = ScreenConfig(
            label = theater.name,
            widthM = theater.screenWidthM,
            heightM = theater.screenHeightM,
            distanceM = seat.distanceM,
            screenBottomM = theater.screenBottomM,
        ),
        riserHeightM = seat.riserHeightM,
        room = TheaterEnvironment.computeRoom(theater),
    )
    applyViewOrigin()
    Log.i(TAG, "Seat riser height: ${theaterState.value.riserHeightM}m (eyeBoost=$eyeBoost)")
    logScreenPosition()
    repositionTheater()

    // Cross-fade room acoustics to match the new theater size
    if (roomAcousticsEnabled.value) {
      roomAcousticsController.applyRoom(theaterState.value.room, activityScope)
    }
  }

  // Track the audio session ID that spatial audio was last wired for.
  // Re-wires when session changes (new content, bumper→movie transition).
  private var lastWiredAudioSessionId = 0

  /** Wire spatial audio and room acoustics to the current ExoPlayer audio session. */
  private fun wireSpatialAudio() {
    val screenEnt = screenEntity ?: return
    val currentSessionId = exoPlayerSource.player.audioSessionId

    // Layer 1: Spatial audio — anchor sound to screen position via Dolby Atmos
    if (spatialAudioEnabled.value && currentSessionId != lastWiredAudioSessionId) {
      val regId = 1
      spatialAudioFeature.registerAudioSessionId(regId, currentSessionId)

      val audioFormat = exoPlayerSource.player.audioFormat
      val channelCount = audioFormat?.channelCount ?: 2

      when {
        channelCount > 2 -> {
          // Multichannel (5.1/7.1/Atmos) — native SOUNDFIELD rendering
          screenEnt.setComponent(AudioSessionId(regId, AudioType.SOUNDFIELD))
        }
        channelCount == 2 -> {
          // Stereo — position L/R channels in screen's local space
          screenEnt.setComponent(AudioSessionId(regId, AudioType.STEREO))
          screenEnt.setComponent(AudioSessionStereoOffsets(
              left = Vector3(-1f, 0f, 0f),
              right = Vector3(1f, 0f, 0f),
          ))
        }
        else -> {
          // Mono
          screenEnt.setComponent(AudioSessionId(regId, AudioType.MONO))
        }
      }

      lastWiredAudioSessionId = currentSessionId
      Log.i(TAG, "Spatial audio wired: channels=$channelCount sessionId=$currentSessionId")
    }

    // Layer 2: Room acoustics — reverb matched to theater size
    if (roomAcousticsEnabled.value) {
      roomAcousticsController.enable(currentSessionId)
      roomAcousticsController.applyRoom(theaterState.value.room, activityScope)
      Log.i(TAG, "Room acoustics enabled for ${theaterState.value.screen.label}")
    }
  }

  /** Respawn all positioned entities using current anchor and theater state. */
  private fun repositionTheater() {
    spawnEnvironment()
    respawnScreen()
    if (browsePanelVisible.value) {
      spawnBrowsePanel()
    }
    if (controlsPanelVisible.value) {
      showControlsPanel()
    }
  }

  override fun onRecenter(isUserInitiated: Boolean) {
    super.onRecenter(isUserInitiated)
    Log.i(TAG, "onRecenter: userInitiated=$isUserInitiated")
    // A recenter moves world coordinates; a dragged panel position is stale.
    customBrowsePose = null
    // Preserve current riser height — recenter reorients but keeps seat
    // elevation; captureAnchor re-runs the seated-height calibration.
    if (!captureAnchor()) {
      Log.w(TAG, "onRecenter: failed to capture anchor, retaining previous")
    }
    repositionTheater()
  }

  private fun logScreenPosition() {
    val a = anchor ?: return
    val headPose =
        Query.where { has(AvatarAttachment.id) }
            .eval()
            .filter { it.isLocal() && it.getComponent<AvatarAttachment>().type == "head" }
            .firstOrNull()
            ?.getComponent<Transform>()
            ?.transform

    val screen = theaterState.value.screen
    val screenPose = TheaterLayout.screenPose(a, screen)
    val screenPos = screenPose.t

    val headPos = headPose?.t ?: Vector3(0f, 0f, 0f)
    val relativePos = Vector3(
        screenPos.x - headPos.x,
        screenPos.y - headPos.y,
        screenPos.z - headPos.z,
    )
    val distToScreen = Math.sqrt(
        (relativePos.x * relativePos.x + relativePos.y * relativePos.y + relativePos.z * relativePos.z).toDouble()
    ).toFloat()

    Log.i(TAG, "Screen: ${screen.label} at ${screen.distanceM}m")
    Log.i(TAG, "  Screen world pos: $screenPos")
    Log.i(TAG, "  Head world pos: $headPos")
    Log.i(TAG, "  Relative to head: $relativePos")
    Log.i(TAG, "  Distance from head: ${String.format("%.2f", distToScreen)}m")
    Log.i(TAG, "  Screen size: ${screen.widthM}m x ${screen.heightM}m, Screen center: ${screen.screenCenterY}m")
  }

  override fun registerPanels(): List<PanelRegistration> {
    return listOf(
        // Main screen panel — direct-to-surface video rendering via compositor layer.
        // ExoPlayer renders directly to the surface provided by the SDK, bypassing the
        // Android View system for significantly sharper video output.
        VideoSurfacePanelRegistration(
            R.id.screen_panel,
            surfaceConsumer = { _, surface ->
              exoPlayerSource.attachSurface(surface)
            },
            settingsCreator = {
              val screen = theaterState.value.screen
              val (fitW, fitH) = fitVideoToScreen(screen.widthM, screen.heightM, videoWidth, videoHeight)
              val pixW = if (videoWidth > 0) videoWidth else 1920
              val pixH = if (videoHeight > 0) videoHeight else 1080
              MediaPanelSettings(
                  shape = QuadShapeOptions(width = fitW, height = fitH),
                  display = PixelDisplayOptions(width = pixW, height = pixH),
                  rendering = MediaPanelRenderOptions(isDRM = false, zIndex = 0),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
              )
            },
        ),
        // Jellyfin browse panel (shown/hidden via A button)
        ComposeViewPanelRegistration(
            R.id.browse_panel,
            composeViewCreator = { _, ctx ->
              ComposeView(ctx).apply {
                setContent {
                  BrowsePanel(
                      jellyfinClient = jellyfinClient,
                      onMediaSelected = { item ->
                        activityScope.launch {
                          playbackReporter.stopReporting()

                          // Fetch fresh position from server (cached data may be stale)
                          val freshItem = jellyfinClient.getItemFresh(item.id) ?: item
                          val url = jellyfinClient.getStreamUrl(freshItem.id)
                          val resumeMs = PlaybackReporter.computeResumePositionMs(
                              freshItem.playbackPositionTicks,
                              freshItem.runTimeTicks,
                          )
                          val startPaused = resumeMs > 0
                          Log.i(TAG, "Media selected: '${freshItem.name}' resumeMs=$resumeMs startPaused=$startPaused")
                          // Reset spatial audio so it re-wires with the movie's audio session
                          lastWiredAudioSessionId = 0
                          roomAcousticsController.disable()
                          exoPlayerSource.connect(url, resumeMs, startPaused)
                          playbackReporter.startReporting(freshItem.id)

                          // Hide browse panel after selecting media
                          dismissBrowsePanel()
                        }
                      },
                      currentScreen = theaterState.value.screen,
                      onTheaterSelected = { theater, seat ->
                        applyTheaterPreset(theater, seat)
                      },
                      spatialAudioEnabled = spatialAudioEnabled.value,
                      onSpatialAudioToggled = { enabled ->
                        spatialAudioEnabled.value = enabled
                        audioSettings.spatialAudioEnabled = enabled
                        Log.i(TAG, "Spatial audio toggled: $enabled")
                        // Takes effect on next STATE_READY
                      },
                      roomAcousticsEnabled = roomAcousticsEnabled.value,
                      onRoomAcousticsToggled = { enabled ->
                        roomAcousticsEnabled.value = enabled
                        audioSettings.roomAcousticsEnabled = enabled
                        if (enabled) {
                          roomAcousticsController.enable(exoPlayerSource.player.audioSessionId)
                          roomAcousticsController.applyRoom(theaterState.value.room, activityScope)
                        } else {
                          roomAcousticsController.disable()
                        }
                        Log.i(TAG, "Room acoustics toggled: $enabled")
                      },
                  )
                }
              }
            },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(width = 1.1f, height = 0.75f),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(dpPerMeter = 800f),
                  input = PanelInputOptions(
                      ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
                  ),
              )
            },
        ),
        // Playback controls HUD (auto-shown on pause, toggled via Y button)
        ComposeViewPanelRegistration(
            R.id.controls_panel,
            composeViewCreator = { _, ctx ->
              ComposeView(ctx).apply {
                setContent {
                  PlaybackControlsPanel(
                      exoPlayerSource = exoPlayerSource,
                      onPlayPause = { handlePlayPause() },
                      onStop = { handleStop() },
                      onSeekTo = { positionMs -> handleSeekTo(positionMs) },
                      onHide = { dismissControlsPanel() },
                      onButtonHover = { hapticPulse(0.18f, 8) },
                  )
                }
              }
            },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(width = 0.7f, height = 0.33f),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(dpPerMeter = 800f),
                  input = PanelInputOptions(
                      ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
                  ),
              )
            },
        ),
        // Subtitle overlay — transparent, non-interactive, sized to the screen.
        // The movie renders as a compositor layer at zIndex 0; this panel must
        // composite ABOVE it or the video covers the subtitles, so we wrap the
        // settings to force the panel's layer zIndex to 1.
        ComposeViewPanelRegistration(
            R.id.subtitle_panel,
            composeViewCreator = { _, ctx ->
              ComposeView(ctx).apply {
                setContent {
                  SubtitlePanel(exoPlayerSource = exoPlayerSource)
                }
              }
            },
            settingsCreator = {
              val screen = theaterState.value.screen
              // Match the fitted (letterboxed) video frame exactly, so cue
              // geometry maps 1:1 onto the picture.
              val (fitW, fitH) = fitVideoToScreen(screen.widthM, screen.heightM, videoWidth, videoHeight)
              val base = UIPanelSettings(
                  shape = QuadShapeOptions(width = fitW, height = fitH),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(dpPerMeter = 300f),
              )
              object : PanelSettings {
                override fun toPanelConfigOptions(): PanelConfigOptions {
                  val opts = base.toPanelConfigOptions()
                  opts.layerConfig?.let { it.zIndex = 1 }
                  return opts
                }
              }
            },
        ),
    )
  }
}
