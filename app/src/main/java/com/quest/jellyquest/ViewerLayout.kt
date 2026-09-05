package com.quest.jellyquest

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3

/**
 * Pure positioning logic for viewer-relative objects (control panels).
 * These objects follow the user as they move or change elevation.
 *
 * Uses anchor for XZ direction (panel is always left of screen, not left of gaze)
 * but riser height for Y (follows seat elevation).
 * When free movement is added, XZ will use viewer position instead.
 */
object ViewerLayout {

    const val SEATED_EYE_HEIGHT = 1.1f

    // Browse panel placement relative to viewer: close by, off to the left at
    // roughly 45°, angled to face the viewer — out of the movie screen's
    // sightline and clear of the playback HUD. Also grabbable, so this is just
    // the starting position.
    const val BROWSE_FORWARD = 0.55f
    const val BROWSE_LEFT = 0.6f
    const val BROWSE_BELOW_EYE = 0.1f
    const val BROWSE_TILT_DEG = 10f

    // Grab handle bar above the browse panel (drag target for PanelDragSystem)
    const val HANDLE_HALF_WIDTH = 0.11f
    const val HANDLE_HALF_THICKNESS = 0.016f
    const val BROWSE_PANEL_HEIGHT = 0.75f
    const val HANDLE_LIFT = BROWSE_PANEL_HEIGHT / 2f + 0.05f

    /** Handle bar pose: floats just above the browse panel's top edge. */
    fun browseHandlePose(panelPose: Pose): Pose {
        return Pose(
            Vector3(panelPose.t.x, panelPose.t.y + HANDLE_LIFT, panelPose.t.z),
            panelPose.q,
        )
    }

    // Playback controls HUD placement relative to viewer — centered, below the
    // sight line to the screen, tilted up like a remote resting on a lap tray.
    const val CONTROLS_FORWARD = 0.55f
    const val CONTROLS_BELOW_EYE = 0.32f
    const val CONTROLS_TILT_DEG = 25f

    // Armrest dimensions and placement
    const val ARMREST_LENGTH = 0.45f       // front to back
    const val ARMREST_WIDTH = 0.06f        // side to side
    const val ARMREST_HEIGHT = 0.04f       // thickness of the padded top
    const val ARMREST_SIDE_OFFSET = 0.30f  // distance to each side of center
    const val ARMREST_FORWARD_OFFSET = 0.10f // slightly forward of body center
    const val ARMREST_BELOW_EYE = 0.45f    // below seated eye height (elbow level)

    /** Position an armrest at the viewer's side. */
    fun armrestPose(anchor: Anchor, riserHeightM: Float, isLeft: Boolean): Pose {
        val lateral = if (isLeft) anchor.left else anchor.right
        val xz = anchor.position +
            anchor.forward * ARMREST_FORWARD_OFFSET +
            lateral * ARMREST_SIDE_OFFSET
        val y = SEATED_EYE_HEIGHT + riserHeightM - ARMREST_BELOW_EYE
        return Pose(Vector3(xz.x, y, xz.z), anchor.rotation)
    }

    /**
     * Position the playback controls HUD centered in front of the viewer,
     * below the sight line to the screen so it never occludes the movie.
     */
    fun controlsPanelPose(anchor: Anchor, riserHeightM: Float): Pose {
        val xz = anchor.position + anchor.forward * CONTROLS_FORWARD
        val position = Vector3(xz.x, SEATED_EYE_HEIGHT + riserHeightM - CONTROLS_BELOW_EYE, xz.z)

        val dx = position.x - anchor.position.x
        val dz = position.z - anchor.position.z
        val yawDeg = Math.toDegrees(Math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
        return Pose(position, Quaternion(CONTROLS_TILT_DEG, yawDeg, 0f))
    }

    /**
     * Position the browse panel to the left of the screen direction,
     * at the viewer's current seated eye height (including riser).
     */
    fun browsePanelPose(anchor: Anchor, riserHeightM: Float): Pose {
        val xz = anchor.position +
            anchor.forward * BROWSE_FORWARD +
            anchor.left * BROWSE_LEFT
        val position = Vector3(xz.x, SEATED_EYE_HEIGHT + riserHeightM - BROWSE_BELOW_EYE, xz.z)

        // Face toward anchor position (not current gaze) with tablet tilt
        val dx = position.x - anchor.position.x
        val dz = position.z - anchor.position.z
        val yawDeg = Math.toDegrees(Math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
        return Pose(position, Quaternion(BROWSE_TILT_DEG, yawDeg, 0f))
    }
}
