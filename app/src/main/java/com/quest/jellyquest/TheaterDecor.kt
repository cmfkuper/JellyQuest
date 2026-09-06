package com.quest.jellyquest

import com.meta.spatial.core.Color4
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3

/**
 * Procedural theater furnishings: stadium seating with a center aisle, aisle
 * step lights, acoustic wall panels, ceiling light strips, and a stage apron.
 *
 * Pure geometry — returns [DecorPiece]s for the activity to spawn. Everything
 * is built from flat-colored boxes so every surface participates in the
 * ambient screen-lighting system; [DecorPiece.gain] sets how strongly each
 * piece reacts (fixtures glow, the back of the house stays dim).
 */
object TheaterDecor {

    // Matches the 12° stadium rake used by the seat presets.
    private const val RAKE = 0.213f

    private const val ROW_DEPTH = 1.7f
    private const val AISLE_HALF_WIDTH = 0.7f
    private const val BENCH_HEIGHT = 0.42f
    private const val BACKREST_HEIGHT = 1.05f

    private val SEAT_FABRIC = Color4(0.30f, 0.06f, 0.08f, 1f)   // dark IMAX red
    private val SEAT_BACK = Color4(0.24f, 0.05f, 0.07f, 1f)
    private val TIER = Color4(0.09f, 0.09f, 0.10f, 1f)
    private val AISLE_LIGHT = Color4(0.55f, 0.35f, 0.12f, 1f)   // warm amber
    private val WALL_PANEL = Color4(0.05f, 0.07f, 0.12f, 1f)    // deep blue
    private val CURTAIN = Color4(0.16f, 0.02f, 0.035f, 1f)      // deep red velvet
    private val TUNGSTEN_CAN = Color4(0.48f, 0.34f, 0.19f, 1f)  // warm old-bulb glow
    private val STAGE = Color4(0.05f, 0.05f, 0.06f, 1f)

    /**
     * Build the full decor set. [seatDistances] are the preset's seat rows
     * (defines where seating starts/ends); the viewer sits at
     * [screen].distanceM and their row is left empty.
     */
    /**
     * [includeSeating] gates the tiers/benches/backrests — hybrid rooms get
     * those from a textured GLB and only want the light-reactive fixtures.
     */
    fun build(
        anchor: Anchor,
        screen: ScreenConfig,
        room: RoomGeometry,
        seatDistances: List<Float>,
        includeSeating: Boolean = true,
    ): List<DecorPiece> {
        if (seatDistances.isEmpty()) return emptyList()
        val pieces = mutableListOf<DecorPiece>()
        val frontSeat = seatDistances.min()
        val backSeat = seatDistances.max()

        fun poseAt(forwardOffset: Float, lateral: Float = 0f, y: Float = 0f) = Pose(
            Vector3(
                anchor.position.x + anchor.forward.x * forwardOffset + anchor.left.x * lateral,
                y,
                anchor.position.z + anchor.forward.z * forwardOffset + anchor.left.z * lateral,
            ),
            anchor.rotation,
        )

        // --- Stadium seating rows ---
        val rowWidth = room.widthFront - 3.0f
        val sectionWidth = (rowWidth / 2f - AISLE_HALF_WIDTH).coerceAtLeast(1.5f)
        val sectionCenter = AISLE_HALF_WIDTH + sectionWidth / 2f
        val seatSpan = (backSeat - frontSeat).coerceAtLeast(0.1f)

        var rowDist = frontSeat
        var rowIndex = 0
        while (rowDist <= backSeat + 0.1f) {
            val tierY = RAKE * (rowDist - frontSeat)
            val forwardOffset = screen.distanceM - rowDist
            // How hard this row catches screen light: strong up front, dim in back.
            val rowGain = 1.3f - 0.8f * ((rowDist - frontSeat) / seatSpan)

            // Tier platform (full width, floor to this row's height).
            if (includeSeating) {
                pieces += DecorPiece(
                    min = Vector3(-rowWidth / 2f, 0f, -ROW_DEPTH / 2f),
                    max = Vector3(rowWidth / 2f, tierY + 0.04f, ROW_DEPTH / 2f),
                    color = TIER,
                    pose = poseAt(forwardOffset),
                    gain = rowGain * 0.8f,
                )
            }

            // Aisle step lights on the tier's screen-side edge (every other row
            // — enough to read as a lit aisle at a fraction of the entities).
            if (rowIndex % 2 == 0) {
                for (side in listOf(-1f, 1f)) {
                    pieces += DecorPiece(
                        min = Vector3(-0.15f, tierY + 0.04f, 0.55f),
                        max = Vector3(0.15f, tierY + 0.075f, 0.85f),
                        color = AISLE_LIGHT,
                        pose = poseAt(forwardOffset, lateral = side * AISLE_HALF_WIDTH),
                        gain = 2.5f,
                    )
                }
            }

            // Leave the viewer's own row empty so they aren't inside a seat.
            val isViewerRow = kotlin.math.abs(rowDist - screen.distanceM) < ROW_DEPTH * 0.6f
            if (includeSeating && !isViewerRow) {
                for (side in listOf(-1f, 1f)) {
                    val lateral = side * sectionCenter
                    // Local +Z faces the screen: cushion forward, backrest on
                    // the -Z (rear) side, like seats that actually face the movie.
                    pieces += DecorPiece(
                        min = Vector3(-sectionWidth / 2f, tierY + 0.04f, -0.25f),
                        max = Vector3(sectionWidth / 2f, tierY + BENCH_HEIGHT, 0.30f),
                        color = SEAT_FABRIC,
                        pose = poseAt(forwardOffset, lateral),
                        gain = rowGain,
                    )
                    pieces += DecorPiece(
                        min = Vector3(-sectionWidth / 2f, tierY + 0.30f, -0.38f),
                        max = Vector3(sectionWidth / 2f, tierY + BACKREST_HEIGHT, -0.25f),
                        color = SEAT_BACK,
                        pose = poseAt(forwardOffset, lateral),
                        gain = rowGain,
                    )
                }
            }
            rowDist += ROW_DEPTH
            rowIndex++
        }

        // (Acoustic wall panels are framed, fabric-textured meshes in the
        // theater GLB now — see theater_imax.py build_baffles.)

        // (Ceiling lights removed pending a proper reference — the tungsten
        // can attempt didn't read as real fixtures.)

        // (Screen-flanking curtains are AI-generated velvet in the theater
        // GLB now — see theater_imax.py build_curtains.)

        // --- Stage apron under the screen ---
        pieces += DecorPiece(
            min = Vector3(-screen.widthM * 0.46f, 0f, -0.55f),
            max = Vector3(screen.widthM * 0.46f, (screen.screenBottomM - 0.05f).coerceAtLeast(0.2f), 0.55f),
            color = STAGE,
            pose = poseAt(screen.distanceM - 0.6f),
            gain = 1.5f,
        )

        return pieces
    }
}

/** One flat-colored box of theater decor, with its light-response gain. */
data class DecorPiece(
    val min: Vector3,
    val max: Vector3,
    val color: Color4,
    val pose: Pose,
    val gain: Float,
)
