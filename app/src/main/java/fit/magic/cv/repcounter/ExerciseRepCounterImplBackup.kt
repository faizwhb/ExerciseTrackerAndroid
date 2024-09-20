// Copyright (c) 2024 Magic Tech Ltd

package fit.magic.cv.repcounter

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import fit.magic.cv.PoseLandmarkerHelper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min


class ExerciseRepCounterImplBackup : ExerciseRepCounter() {

    private var leftStage = "up"
    private var rightStage = "up"
    private var leftLockedAngle: Float? = null
    private var rightLockedAngle: Float? = null
    private var activeLeg: String? = null
    private var lastActiveLeg = "left"

    private val ANGLE_THRESHOLD = 10f
    private val alpha = 0.4f
    private val smoothedLandmarks = mutableMapOf<Int, NormalizedLandmark>()
    private val MIN_ANGLE_FOR_REP = 95f
    private val STRAIGHT_LEG_THRESHOLD = 160f

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

        // Lock-in logic
        if (leftLockedAngle == null || abs(leftKneeAngle - leftLockedAngle!!) > ANGLE_THRESHOLD) {
            leftLockedAngle = leftKneeAngle
        }
        if (rightLockedAngle == null || abs(rightKneeAngle - rightLockedAngle!!) > ANGLE_THRESHOLD) {
            rightLockedAngle = rightKneeAngle
        }

        // Rep counter logic
        if (activeLeg == null || activeLeg == "left") {
            if (leftLockedAngle!! > STRAIGHT_LEG_THRESHOLD) {
                leftStage = "up"
                activeLeg = null
            } else if (leftLockedAngle!! < MIN_ANGLE_FOR_REP && leftStage == "up") {
                leftStage = "down"
                incrementRepCount()
                activeLeg = "left"
                lastActiveLeg = "left"
            }
        }

        if (activeLeg == null || activeLeg == "right") {
            if (rightLockedAngle!! > STRAIGHT_LEG_THRESHOLD) {
                rightStage = "up"
                activeLeg = null
            } else if (rightLockedAngle!! < MIN_ANGLE_FOR_REP && rightStage == "up") {
                rightStage = "down"
                incrementRepCount()
                activeLeg = "right"
                lastActiveLeg = "right"
            }
        }

        // Update progress
        val progressAngle = min(leftLockedAngle!!, rightLockedAngle!!)
        // val progressAngle = if (lastActiveLeg == "left") leftLockedAngle!! else rightLockedAngle!!
        val normalizedAngle = maxOf(0f, minOf((180f - progressAngle) / 90f, 1f))
        sendProgressUpdate(normalizedAngle)

        // Send feedback
        provideFeedback(leftLockedAngle!!, rightLockedAngle!!, normalizedAngle)
    }

    private fun provideFeedback(leftAngle: Float, rightAngle: Float, normalizedAngle: Float) {
        when {
            normalizedAngle < 0.5f ->
                sendFeedbackMessage("Go deeper into your lunge for better results.")
            normalizedAngle > 0.8f ->
                sendFeedbackMessage("Excellent form! You're getting a great stretch.")
            activeLeg != null ->
                sendFeedbackMessage("Good lunge! Keep going.")
            else ->
                sendFeedbackMessage("Stand straight and prepare for your next lunge.")
        }
    }
}