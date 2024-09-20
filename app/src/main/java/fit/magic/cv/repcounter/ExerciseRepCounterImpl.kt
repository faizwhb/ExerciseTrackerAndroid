// Copyright (c) 2024 Magic Tech Ltd

package fit.magic.cv.repcounter

import android.util.Log
import com.google.android.material.animation.Positioning
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import fit.magic.cv.PoseLandmarkerHelper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.max


class ExerciseRepCounterImpl : ExerciseRepCounter() {

    private val minAngle = mutableListOf<Float>()
    private val lungeActive = mutableListOf<Int>()
    private val forwardMode = mutableListOf<Int>()
    private val smoothAngles = mutableListOf<Float>()
    private var prevSmoothAngle = 0f
    private var maxPositionReached = false
    private var repAlreadyCounted = false
    private var MIN_ANGLE_FOR_REP = 120f
    private var MAX_ANGLE_FOR_REP = 90f
    private val alpha = 0.2f
    private val smoothedLandmarks = mutableMapOf<Int, NormalizedLandmark>()

    override fun setResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        val poseLandmarkerResult = resultBundle.results.firstOrNull()

        // No pose detection
        if (poseLandmarkerResult == null) {
            sendFeedbackMessage("No pose detected")
            return
        }

        // no landmarks found by pose landmarker
        val landmarks = poseLandmarkerResult.landmarks()
        if (landmarks.isEmpty()) {
            sendFeedbackMessage("No pose detected")
            return
        }

        try {
            val smoothedLandmarks = smoothLandmarks(landmarks[0])
            val landmarkDict = convertLandmarksToDictionary(smoothedLandmarks)
            val angles = extractAngles(landmarkDict)

            // Process the angles (implementation depends on your specific requirements)
            processAngles(angles)
        } catch (e: Exception) {
            return
        }
    }

    private fun convertLandmarksToDictionary(landmarks: List<NormalizedLandmark>): Map<String, NormalizedLandmark> {
        val landmarkDict = mutableMapOf<String, NormalizedLandmark>()
        val landmarkNames = listOf(
            "LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE",
            "RIGHT_HIP", "RIGHT_KNEE", "RIGHT_ANKLE"
        )
        val landmarkIndices = listOf(23, 25, 27, 24, 26, 28)

        for ((name, index) in landmarkNames.zip(landmarkIndices)) {
            if (index < landmarks.size) {
                landmarkDict[name] = landmarks[index]
            } else {
                throw IllegalStateException("Landmark index $index is out of bounds")
            }
        }

        return landmarkDict
    }
    private fun smoothLandmarks(landmarks: List<NormalizedLandmark>): List<NormalizedLandmark> {
        return landmarks.mapIndexed { index, landmark ->
            if (index !in smoothedLandmarks) {
                smoothedLandmarks[index] = landmark
            } else {
                smoothedLandmarks[index] = NormalizedLandmark.create(
                    alpha * landmark.x() + (1 - alpha) * smoothedLandmarks[index]!!.x(),
                    alpha * landmark.y() + (1 - alpha) * smoothedLandmarks[index]!!.y(),
                    alpha * landmark.z() + (1 - alpha) * smoothedLandmarks[index]!!.z(),
                    landmark.presence(),
                    landmark.visibility()
                )
            }
            smoothedLandmarks[index]!!
        }
    }

    private fun extractAngles(landmarkDict: Map<String, NormalizedLandmark>): Map<String, Float> {
        val angles = mutableMapOf<String, Float>()

        try {
            angles["LEFT_KNEE"] = calculateAngle(
                landmarkDict["LEFT_HIP"]!!,
                landmarkDict["LEFT_KNEE"]!!,
                landmarkDict["LEFT_ANKLE"]!!
            )
            angles["RIGHT_KNEE"] = calculateAngle(
                landmarkDict["RIGHT_HIP"]!!,
                landmarkDict["RIGHT_KNEE"]!!,
                landmarkDict["RIGHT_ANKLE"]!!
            )
        } catch (e: NullPointerException) {
            throw IllegalStateException("Missing required landmarks for angle calculation")
        }

        return angles
    }

    private fun calculateAngle(a: NormalizedLandmark, b: NormalizedLandmark, c: NormalizedLandmark): Float {
        val radians = atan2(c.y() - b.y(), c.x() - b.x()) - atan2(a.y() - b.y(), a.x() - b.x())
        var angle = abs(radians * 180f / PI.toFloat())
        if (angle > 180f) {
            angle = 360f - angle
        }
        return angle
    }

    private fun processAngles(angles: Map<String, Float>) {
        val leftKneeAngle = angles["LEFT_KNEE"] ?: return
        val rightKneeAngle = angles["RIGHT_KNEE"] ?: return

        val currentMinAngle = min(leftKneeAngle, rightKneeAngle)
        minAngle.add(currentMinAngle)

        val normalizedAngle = max(0f, min((MIN_ANGLE_FOR_REP - currentMinAngle) / (MIN_ANGLE_FOR_REP - MAX_ANGLE_FOR_REP), 1f))

        // Determine if lunge is active
        lungeActive.add(if (currentMinAngle < MIN_ANGLE_FOR_REP) 100 else 0)

        // Determine max_position_reached
        if (lungeActive.last() == 100 && currentMinAngle < MAX_ANGLE_FOR_REP) {
            if (minAngle.size >= 3 && minAngle[minAngle.size - 2] < minAngle.last() && minAngle[minAngle.size - 3] > minAngle[minAngle.size - 2]) {
                maxPositionReached = true
            }
        } else if (lungeActive.last() == 0) {
            maxPositionReached = false
        }

        // Determine forward_mode
        forwardMode.add(when {
            lungeActive.last() == 0 -> 0
            lungeActive.last() == 100 && !maxPositionReached -> 100
            else -> 0
        })

        // Update smooth angles and count reps
        if (lungeActive.last() == 100 && !maxPositionReached) {
            prevSmoothAngle = max(normalizedAngle, prevSmoothAngle)
            smoothAngles.add(prevSmoothAngle)
        } else if (lungeActive.last() == 100 && maxPositionReached) {
            prevSmoothAngle = min(normalizedAngle, prevSmoothAngle)
            smoothAngles.add(prevSmoothAngle)
            if (!repAlreadyCounted) {
                incrementRepCount()
                repAlreadyCounted = true
            }
        } else if (lungeActive.last() == 0) {
            prevSmoothAngle = 0f
            smoothAngles.add(prevSmoothAngle)
            repAlreadyCounted = false
        }

        // Update progress
        sendProgressUpdate(smoothAngles.last().toFloat())
        updateDebugValues(smoothAngles.last().toFloat(), lungeActive.last(), maxPositionReached, currentMinAngle)
        // Send feedback
        // provideFeedback(normalizedAngle)
    }
    private fun updateDebugValues(normalized: Float, lungeActive: Int, maxPositionReached: Boolean, currentMinAngle: Float) {
        Log.d("AngleDebug", " Normalized: $normalized, LungeActive: $lungeActive, maxPositionReached: $maxPositionReached, MinAngle: $currentMinAngle")
    }

    private fun provideFeedback(normalizedAngle: Float) {
        when {
            normalizedAngle < 0.5f ->
                sendFeedbackMessage("Go deeper into your lunge for better results.")
            normalizedAngle > 0.8f ->
                sendFeedbackMessage("Excellent form! You're getting a great stretch.")
        }
    }
}