package com.okanetsolutions.briareus.quest

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.okanetsolutions.briareus.core.Placement
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turns a [Placement] (degrees around the user, metres away) into a pose in the room and back, around [anchor]: the
 * user's head when the panels were placed, facing where they looked. Level with the floor, whatever the head's tilt.
 */
class Room(private val anchor: Pose) {
    private val forward: Vector3
    private val right: Vector3

    init {
        val f = anchor.forward().let { Vector3(it.x, 0f, it.z) }
        forward = if (f.length() < 1e-3f) Vector3(0f, 0f, -1f) else f.normalize()
        val r = anchor.right().let { Vector3(it.x, 0f, it.z) }
        right = if (r.length() < 1e-3f) Vector3(1f, 0f, 0f) else r.normalize()
    }

    /** Where [at] is, turned to face the user's head. */
    fun pose(at: Placement): Pose {
        val yaw = Math.toRadians(at.yaw)
        val direction = forward * cos(yaw).toFloat() + right * sin(yaw).toFloat()
        val position = Vector3(anchor.t.x, anchor.t.y + at.height.toFloat(), anchor.t.z) + direction * at.distance.toFloat()
        return Pose(position, Quaternion.lookRotationAroundY(position - anchor.t))
    }

    /** [at] moved to where [position] is, keeping its size. */
    fun placement(position: Vector3, at: Placement): Placement {
        val offset = position - anchor.t
        val along = offset.dot(forward).toDouble()
        val across = offset.dot(right).toDouble()
        return at.copy(yaw = Math.toDegrees(atan2(across, along)), distance = hypot(along, across), height = (position.y - anchor.t.y).toDouble())
    }
}
