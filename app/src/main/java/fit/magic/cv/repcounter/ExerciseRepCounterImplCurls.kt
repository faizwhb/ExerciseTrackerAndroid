// Copyright (c) 2024 Magic Tech Ltd

package fit.magic.cv.repcounter

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import fit.magic.cv.PoseLandmarkerHelper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min


class ExerciseRepCounterImplCurls : ExerciseRepCounter() {

    private var leftStage = "extended"
    private var rightStage = "extended"
    private var leftLockedAngle: Float? = null
    private var rightLockedAngle: Float? = null
    private var activeLeg: String? = null
    private var lastActiveLeg = "left"

    private val ANGLE_THRESHOLD = 5f
    private val alpha = 0.4f
    private val smoothedLandmarks = mutableMapOf<Int, NormalizedLandmark>()

    override fun setResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        val poseLandmarkerResult = resultBundle.results.firstOrNull()

        if (poseLandmarkerResult == null || poseLandmarkerResult.landmarks().isEmpty()) {
            sendFeedbackMessage("No pose detected")
            return
        }

        try {
            val smoothedLandmarks = smoothLandmarks(poseLandmarkerResult.landmarks()[0])
            val landmarkDict = convertLandmarksToDictionary(smoothedLandmarks)
            val angles = extractAngles(landmarkDict)
            processAngles(angles)
        } catch (e: Exception) {
            sendFeedbackMessage("Error processing pose: ${e.message}")
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
            if (leftLockedAngle!! < 90) {
                leftStage = "curled"
                activeLeg = "left"
            } else if (leftLockedAngle!! > 90 && leftStage == "curled") {
                leftStage = "extended"
                incrementRepCount()
                activeLeg = null
                lastActiveLeg = "left"
            }
        }

        if (activeLeg == null || activeLeg == "right") {
            if (rightLockedAngle!! < 90) {
                rightStage = "curled"
                activeLeg = "right"
            } else if (rightLockedAngle!! > 90 && rightStage == "curled") {
                rightStage = "extended"
                incrementRepCount()
                activeLeg = null
                lastActiveLeg = "right"
            }
        }

        // Update progress
        val progressAngle = if (lastActiveLeg == "left") leftLockedAngle!! else rightLockedAngle!!
        val normalizedAngle = max(0f, min((180f - progressAngle) / 90f, 1f))
        sendProgressUpdate(normalizedAngle)

        // Send feedback
        provideFeedback(leftLockedAngle!!, rightLockedAngle!!)
    }

    private fun provideFeedback(leftAngle: Float, rightAngle: Float) {
        when {
            activeLeg == "left" && leftAngle < 45 ->
                sendFeedbackMessage("Great curl with your left leg!")
            activeLeg == "right" && rightAngle < 45 ->
                sendFeedbackMessage("Excellent curl with your right leg!")
            activeLeg == null && (leftAngle > 160 || rightAngle > 160) ->
                sendFeedbackMessage("Good extension. Prepare for the next curl.")
            else ->
                sendFeedbackMessage("Keep going, maintain your form.")
        }
    }
}