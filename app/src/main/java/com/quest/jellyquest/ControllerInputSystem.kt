package com.quest.jellyquest

import android.util.Log
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller

/**
 * Maps controller buttons to app actions. Everything is reachable from the
 * right controller alone; the left controller carries duplicate bindings.
 *
 * Right controller (primary):
 *   A — play/pause
 *   B — stop + return to browse
 *   Thumbstick left/right — seek backward/forward 10s
 *   Thumbstick click — toggle playback controls HUD
 *   Grip (squeeze) — toggle browse panel
 *
 * Left controller (duplicates):
 *   X — toggle browse panel
 *   Y — toggle playback controls HUD
 *   Thumbstick left/right — seek backward/forward 10s
 */
class ControllerInputSystem(
    private val isBrowseVisible: () -> Boolean = { false },
    private val onBrowseToggle: () -> Unit = {},
    private val onControlsToggle: () -> Unit = {},
    private val onPlayPauseToggle: () -> Unit = {},
    private val onStop: () -> Unit = {},
    private val onSeekForward: () -> Unit = {},
    private val onSeekBackward: () -> Unit = {},
) : SystemBase() {

  companion object {
    private const val TAG = "VirtualMonitor"
    private const val SEEK_COOLDOWN_MS = 400L
  }

  // Debounce flags: prevent double-fire if changedButtons persists across frames
  private var aButtonHandled = false
  private var bButtonHandled = false
  private var xButtonHandled = false
  private var yButtonHandled = false
  private var rClickHandled = false
  private var rSqueezeHandled = false

  // Seek cooldown: thumbstick is continuous, so we throttle seek events
  private var lastSeekTime = 0L

  override fun execute() {
    val localPlayerAvatar =
        Query.where { has(AvatarBody.id) }
            .eval()
            .firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
            ?: return

    val avatarBody = localPlayerAvatar.getComponent<AvatarBody>()
    val rightController = avatarBody.rightHand.tryGetComponent<Controller>() ?: return
    val leftController = avatarBody.leftHand.tryGetComponent<Controller>()

    // A button (right controller) → play/pause
    val aDown = (rightController.buttonState and ButtonBits.ButtonA) != 0
    val aChanged = (rightController.changedButtons and ButtonBits.ButtonA) != 0
    if (aDown && aChanged && !aButtonHandled) {
      aButtonHandled = true
      Log.d(TAG, "A button pressed → play/pause")
      onPlayPauseToggle()
    } else if (!aDown && aChanged) {
      aButtonHandled = false
    }

    // B button (right controller) → stop + return to browse
    val bDown = (rightController.buttonState and ButtonBits.ButtonB) != 0
    val bChanged = (rightController.changedButtons and ButtonBits.ButtonB) != 0
    if (bDown && bChanged && !bButtonHandled) {
      bButtonHandled = true
      Log.d(TAG, "B button pressed → stop")
      onStop()
    } else if (!bDown && bChanged) {
      bButtonHandled = false
    }

    // While the browse panel is open, the right thumbstick belongs to it
    // (scrolling the grid) — suppress stick-click HUD toggle and stick seeks,
    // which otherwise fire from accidental presses during scroll flicks.
    val browseOpen = isBrowseVisible()

    // Right thumbstick click → toggle playback controls HUD
    val rClickDown = (rightController.buttonState and ButtonBits.ButtonThumbRClick) != 0
    val rClickChanged = (rightController.changedButtons and ButtonBits.ButtonThumbRClick) != 0
    if (rClickDown && rClickChanged && !rClickHandled) {
      rClickHandled = true
      if (!browseOpen) {
        Log.d(TAG, "Right thumbstick click → toggle playback controls")
        onControlsToggle()
      }
    } else if (!rClickDown && rClickChanged) {
      rClickHandled = false
    }

    // Right grip (squeeze) → toggle browse panel
    val rSqueezeDown = (rightController.buttonState and ButtonBits.ButtonSqueezeR) != 0
    val rSqueezeChanged = (rightController.changedButtons and ButtonBits.ButtonSqueezeR) != 0
    if (rSqueezeDown && rSqueezeChanged && !rSqueezeHandled) {
      rSqueezeHandled = true
      Log.d(TAG, "Right grip → toggle browse")
      onBrowseToggle()
    } else if (!rSqueezeDown && rSqueezeChanged) {
      rSqueezeHandled = false
    }

    // Right thumbstick left/right → seek backward/forward (continuous with cooldown)
    val nowR = System.currentTimeMillis()
    if (!browseOpen && nowR - lastSeekTime >= SEEK_COOLDOWN_MS) {
      val rThumbLeft = (rightController.buttonState and ButtonBits.ButtonThumbRL) != 0
      val rThumbRight = (rightController.buttonState and ButtonBits.ButtonThumbRR) != 0
      if (rThumbLeft) {
        Log.d(TAG, "Right thumbstick left → seek backward")
        onSeekBackward()
        lastSeekTime = nowR
      } else if (rThumbRight) {
        Log.d(TAG, "Right thumbstick right → seek forward")
        onSeekForward()
        lastSeekTime = nowR
      }
    }

    // X button (left controller) → toggle browse panel
    leftController?.let { controller ->
      val xDown = (controller.buttonState and ButtonBits.ButtonX) != 0
      val xChanged = (controller.changedButtons and ButtonBits.ButtonX) != 0
      if (xDown && xChanged && !xButtonHandled) {
        xButtonHandled = true
        Log.d(TAG, "X button pressed → toggle browse")
        onBrowseToggle()
      } else if (!xDown && xChanged) {
        xButtonHandled = false
      }

      // Y button (left controller) → toggle playback controls HUD
      val yDown = (controller.buttonState and ButtonBits.ButtonY) != 0
      val yChanged = (controller.changedButtons and ButtonBits.ButtonY) != 0
      if (yDown && yChanged && !yButtonHandled) {
        yButtonHandled = true
        Log.d(TAG, "Y button pressed → toggle playback controls")
        onControlsToggle()
      } else if (!yDown && yChanged) {
        yButtonHandled = false
      }

      // Left thumbstick left/right → seek backward/forward (continuous with cooldown)
      val now = System.currentTimeMillis()
      if (!browseOpen && now - lastSeekTime >= SEEK_COOLDOWN_MS) {
        val thumbLeft = (controller.buttonState and ButtonBits.ButtonThumbLL) != 0
        val thumbRight = (controller.buttonState and ButtonBits.ButtonThumbLR) != 0
        if (thumbLeft) {
          Log.d(TAG, "Left thumbstick left → seek backward")
          onSeekBackward()
          lastSeekTime = now
        } else if (thumbRight) {
          Log.d(TAG, "Left thumbstick right → seek forward")
          onSeekForward()
          lastSeekTime = now
        }
      }
    }
  }
}
