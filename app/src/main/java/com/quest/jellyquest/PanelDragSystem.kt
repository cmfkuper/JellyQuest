package com.quest.jellyquest

import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Transform
import kotlin.math.sqrt

/**
 * Point-and-hold drag for the browse panel via the handle bar above it.
 *
 * The toolkit's Grabbable component depends on the ISDK interaction stack this
 * app doesn't run, so this system implements the grab directly: aim the right
 * controller at the handle, press and hold the trigger, and the panel follows
 * the controller ray at the grab distance until the trigger is released. The
 * panel re-orients to face the viewer as it moves.
 */
class PanelDragSystem(private val activity: JellyQuestActivity) : SystemBase() {

    companion object {
        // Generous hit sphere around the handle — VR pointing is imprecise.
        private const val GRAB_RADIUS = 0.16f

        // Dracula purple (idle) → pink (hovered) → green (grabbed)
        private val COLOR_IDLE = Color4(0.74f, 0.58f, 0.98f, 1f)
        private val COLOR_HOVER = Color4(1f, 0.475f, 0.776f, 1f)
        private val COLOR_GRABBED = Color4(0.31f, 0.98f, 0.48f, 1f)
    }

    private var dragging = false
    private var dragDistance = 0f
    private var prevTriggerDown = false
    private var hovering = false

    override fun execute() {
        val handle = activity.browseHandleEntity
        val panelPose = activity.currentBrowsePanelPose()
        if (handle == null || panelPose == null) {
            dragging = false
            hovering = false
            return
        }

        val avatarBody = Query.where { has(AvatarBody.id) }
            .eval()
            .firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
            ?.getComponent<AvatarBody>() ?: return
        val rightHand = avatarBody.rightHand
        val controller = rightHand.tryGetComponent<Controller>() ?: return
        val handPose = rightHand.tryGetComponent<Transform>()?.transform ?: return

        val triggerDown = (controller.buttonState and ButtonBits.ButtonTriggerR) != 0
        val origin = handPose.t
        val dir = normalized(handPose.forward())

        if (!dragging) {
            // Hover feedback: recolor + a light haptic tick on hover entry.
            val handlePos = handle.tryGetComponent<Transform>()?.transform?.t
            val hit = handlePos != null && rayHitsSphere(origin, dir, handlePos, GRAB_RADIUS)
            if (hit != hovering) {
                hovering = hit
                setHandleColor(handle, if (hit) COLOR_HOVER else COLOR_IDLE)
                if (hit) activity.hapticPulse(0.25f, 12)
            }
            if (hit && triggerDown && !prevTriggerDown) {
                dragging = true
                dragDistance = length(sub(panelPose.t, origin)).coerceIn(0.35f, 3f)
                setHandleColor(handle, COLOR_GRABBED)
                activity.hapticPulse(0.6f, 25)
            }
        } else if (!triggerDown) {
            dragging = false
            setHandleColor(handle, if (hovering) COLOR_HOVER else COLOR_IDLE)
            activity.hapticPulse(0.35f, 15)
        } else {
            activity.moveBrowsePanel(
                Vector3(
                    origin.x + dir.x * dragDistance,
                    origin.y + dir.y * dragDistance,
                    origin.z + dir.z * dragDistance,
                ),
            )
        }
        prevTriggerDown = triggerDown
    }

    private fun setHandleColor(handle: Entity, color: Color4) {
        handle.setComponent(Material().apply {
            baseColor = color
            unlit = true
        })
    }

    private fun rayHitsSphere(origin: Vector3, dir: Vector3, target: Vector3, radius: Float): Boolean {
        val toTarget = sub(target, origin)
        val proj = dot(toTarget, dir)
        if (proj <= 0f) return false
        val closest = Vector3(
            origin.x + dir.x * proj,
            origin.y + dir.y * proj,
            origin.z + dir.z * proj,
        )
        return length(sub(target, closest)) <= radius
    }

    private fun sub(a: Vector3, b: Vector3) = Vector3(a.x - b.x, a.y - b.y, a.z - b.z)
    private fun dot(a: Vector3, b: Vector3) = a.x * b.x + a.y * b.y + a.z * b.z
    private fun length(v: Vector3) = sqrt(dot(v, v))
    private fun normalized(v: Vector3): Vector3 {
        val len = length(v)
        return if (len > 0f) Vector3(v.x / len, v.y / len, v.z / len) else Vector3(0f, 0f, 1f)
    }
}
