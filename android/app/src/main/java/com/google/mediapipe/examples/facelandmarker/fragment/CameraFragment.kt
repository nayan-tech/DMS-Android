package com.google.mediapipe.examples.facelandmarker.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import com.google.mediapipe.examples.facelandmarker.FaceLandmarkerHelper
import com.google.mediapipe.examples.facelandmarker.MainViewModel
import com.google.mediapipe.examples.facelandmarker.R
import com.google.mediapipe.examples.facelandmarker.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

class CameraFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Face Landmarker"
    }

    private var faceDetectedLast = false
    private var isCalibrating = false
    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    private lateinit var backgroundExecutor: ExecutorService

    private var drowsinessCount = 0
    private var yawnCount = 0
    private var sleepCount = 0

    private var initTime = 0L
    private var drowsyTimer: Long? = null
    private var yawnTimer: Long? = null
    private var sleepTimer: Long? = null

    private val fbDists = mutableListOf<Float>()
    private val mouthRatios = mutableListOf<Float>()
    private val eyeRatios = mutableListOf<Float>()

    private var fbMedian: Float? = null
    private var mouthMedian: Float? = null
    private var eyeMedian: Float? = null

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(R.id.action_camera_to_permissions)
        }
        backgroundExecutor.execute {
            if (faceLandmarkerHelper.isClose()) {
                faceLandmarkerHelper.setupFaceLandmarker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::faceLandmarkerHelper.isInitialized) {
            viewModel.setMaxFaces(faceLandmarkerHelper.maxNumFaces)
            viewModel.setMinFaceDetectionConfidence(faceLandmarkerHelper.minFaceDetectionConfidence)
            viewModel.setMinFaceTrackingConfidence(faceLandmarkerHelper.minFaceTrackingConfidence)
            viewModel.setMinFacePresenceConfidence(faceLandmarkerHelper.minFacePresenceConfidence)
            viewModel.setDelegate(faceLandmarkerHelper.currentDelegate)
            backgroundExecutor.execute { faceLandmarkerHelper.clearFaceLandmarker() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initTime = SystemClock.uptimeMillis()
        backgroundExecutor = Executors.newSingleThreadExecutor()

        fragmentCameraBinding.viewFinder.post {
            setUpCamera()
        }

        backgroundExecutor.execute {
            faceLandmarkerHelper = FaceLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minFaceDetectionConfidence = viewModel.currentMinFaceDetectionConfidence,
                minFaceTrackingConfidence = viewModel.currentMinFaceTrackingConfidence,
                minFacePresenceConfidence = viewModel.currentMinFacePresenceConfidence,
                maxNumFaces = viewModel.currentMaxFaces,
                currentDelegate = viewModel.currentDelegate,
                faceLandmarkerHelperListener = this
            )
        }
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(cameraFacing)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor) { image ->
                    detectFace(image)
                }
            }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectFace(imageProxy: ImageProxy) {
        faceLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = fragmentCameraBinding.viewFinder.display.rotation
    }

    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            val landmarks = resultBundle.result.faceLandmarks().firstOrNull()
            val faceDetectedNow = landmarks != null

            if (!faceDetectedNow) {
                faceDetectedLast = false
                isCalibrating = false
                fragmentCameraBinding.tvCalibrating.visibility = View.GONE
                fragmentCameraBinding.overlay.clearBoundingBox()
                return@runOnUiThread
            }

            val imageWidth = resultBundle.inputImageWidth
            val imageHeight = resultBundle.inputImageHeight

            fun getPoint(index: Int): Pair<Float, Float> {
                val lm = landmarks!![index]
                return Pair(lm.x() * imageWidth, lm.y() * imageHeight)
            }

            val (topHeadX, topHeadY) = getPoint(10)
            val (chinX, chinY) = getPoint(152)
            val (noseX, noseY) = getPoint(1)
            val (mouthTopX, mouthTopY) = getPoint(13)
            val (mouthBottomX, mouthBottomY) = getPoint(14)
            val (mouthLeftX, mouthLeftY) = getPoint(78)
            val (mouthRightX, mouthRightY) = getPoint(308)
            val (leftEyeTopX, leftEyeTopY) = getPoint(159)
            val (leftEyeBottomX, leftEyeBottomY) = getPoint(145)
            val (rightEyeTopX, rightEyeTopY) = getPoint(386)
            val (rightEyeBottomX, rightEyeBottomY) = getPoint(374)

            val leftEye = hypot(leftEyeTopX - leftEyeBottomX, leftEyeTopY - leftEyeBottomY)
            val rightEye = hypot(rightEyeTopX - rightEyeBottomX, rightEyeTopY - rightEyeBottomY)
            val eyeRatio = (leftEye + rightEye) / 2f

            val mouthWidth = hypot(mouthLeftX - mouthRightX, mouthLeftY - mouthRightY)
            val mouthHeight = hypot(mouthTopX - mouthBottomX, mouthTopY - mouthBottomY)
            val mouthRatio = abs(mouthWidth - mouthHeight)
            val currentFbDist = hypot(noseX - (topHeadX + chinX) / 2, noseY - (topHeadY + chinY) / 2)
            val headAngle = abs(Math.toDegrees(atan2(
                (chinY - topHeadY).toDouble(), (chinX - topHeadX).toDouble()
            ))).toFloat()

            val currentTime = SystemClock.uptimeMillis()

            if (!faceDetectedLast && faceDetectedNow) {
                fbDists.clear(); mouthRatios.clear(); eyeRatios.clear()
                fbMedian = null; mouthMedian = null; eyeMedian = null
                initTime = currentTime
                isCalibrating = true
            }

            faceDetectedLast = true

            if (isCalibrating && currentTime - initTime < 5000) {
                fbDists.add(hypot(noseX - (topHeadX + chinX) / 2, noseY - (topHeadY + chinY) / 2))
                mouthRatios.add(mouthRatio)
                eyeRatios.add(eyeRatio)
                fragmentCameraBinding.tvCalibrating.text = "Calibrating... ${5 - ((currentTime - initTime) / 1000)}"
                fragmentCameraBinding.tvCalibrating.visibility = View.VISIBLE
                return@runOnUiThread
            } else if (isCalibrating) {
                if (fbMedian == null && fbDists.isNotEmpty()) fbMedian = fbDists.sorted()[fbDists.size / 2]
                if (mouthMedian == null && mouthRatios.isNotEmpty()) mouthMedian = mouthRatios.sorted()[mouthRatios.size / 2]
                if (eyeMedian == null && eyeRatios.isNotEmpty()) eyeMedian = eyeRatios.sorted()[eyeRatios.size / 2]
                if (listOf(fbMedian, mouthMedian, eyeMedian).any { it == null }) return@runOnUiThread
                fragmentCameraBinding.tvCalibrating.visibility = View.GONE
                isCalibrating = false
            }

            val eyeClosed = eyeRatio < eyeMedian!! * 0.8
            val mouthOpen = mouthRatio < mouthMedian!! * 0.5
            val headTilt = headAngle < 70f || headAngle > 110f
            val fbTilt = currentFbDist < fbMedian!! * 0.5f || currentFbDist > fbMedian!! * 1.5f

            var label: String? = null

            if (eyeClosed && !headTilt) {
                if (drowsyTimer == null) drowsyTimer = currentTime
                if (currentTime - drowsyTimer!! > 1500) {
                    drowsinessCount++
                    drowsyTimer = null
                    label = "Drowsy"
                }
            } else drowsyTimer = null

            if (mouthOpen) {
                if (yawnTimer == null) yawnTimer = currentTime
                if (currentTime - yawnTimer!! > 1500) {
                    yawnCount++
                    yawnTimer = null
                    label = "Yawn"
                }
            } else yawnTimer = null

            if ((eyeClosed && headTilt)|| fbTilt) {
                if (sleepTimer == null) sleepTimer = currentTime
                if (currentTime - sleepTimer!! > 2500) {
                    sleepCount++
                    sleepTimer = null
                    label = "Sleep"
                }
            } else sleepTimer = null

            if (label != null && landmarks != null) {
                fragmentCameraBinding.overlay.setBoundingBoxFromLandmarks(landmarks, label)
            } else {
                fragmentCameraBinding.overlay.clearBoundingBox()
            }
            fragmentCameraBinding.tvDrowsyCount.text = "Drowsy: $drowsinessCount"
            fragmentCameraBinding.tvYawnCount.text = "Yawn: $yawnCount"
            fragmentCameraBinding.tvSleepCount.text = "Sleep: $sleepCount"

            //fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                //String.format("%d ms", resultBundle.inferenceTime)

            fragmentCameraBinding.overlay.setResults(
                resultBundle.result,
                imageHeight,
                imageWidth,
                RunningMode.LIVE_STREAM
            )
        }
    }

    override fun onEmpty() {
        fragmentCameraBinding.overlay.clear()
        fragmentCameraBinding.overlay.clearBoundingBox()
        activity?.runOnUiThread {
            drowsinessCount = 0
            yawnCount = 0
            sleepCount = 0
            fbDists.clear(); mouthRatios.clear(); eyeRatios.clear()
            fbMedian = null; mouthMedian = null; eyeMedian = null
            isCalibrating = false; faceDetectedLast = false
            fragmentCameraBinding.tvDrowsyCount.text = "Drowsy: 0"
            fragmentCameraBinding.tvYawnCount.text = "Yawn: 0"
            fragmentCameraBinding.tvSleepCount.text = "Sleep: 0"
            fragmentCameraBinding.tvCalibrating.visibility = View.GONE
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            /*if (errorCode == FaceLandmarkerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    FaceLandmarkerHelper.DELEGATE_CPU, false
                )
            }*/
        }
    }
}
