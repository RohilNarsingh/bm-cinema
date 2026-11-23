package com.bmcam.cinema

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorCorrectionGains
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val SHUTTER_NS = longArrayOf(
        125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L,
        16_666_667L, 20_000_000L, 33_333_333L, 40_000_000L, 66_666_667L, 83_333_333L,
        125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L
    )
    private val SHUTTER_LABEL = arrayOf(
        "1/8000","1/4000","1/2000","1/1000","1/500","1/250","1/125","1/60",
        "1/50","1/30","1/25","1/15","1/12","1/8","1/4","1/2","1s"
    )

    private lateinit var textureView: TextureView
    private lateinit var tvStatus: TextView
    private lateinit var tvShutter: TextView; private lateinit var tvIso: TextView
    private lateinit var tvWb: TextView;     private lateinit var tvFocus: TextView
    private lateinit var tvZoom: TextView
    private lateinit var sbShutter: SeekBar; private lateinit var sbIso: SeekBar
    private lateinit var sbWb: SeekBar;      private lateinit var sbFocus: SeekBar
    private lateinit var sbZoom: SeekBar
    private lateinit var spFps: Spinner
    private lateinit var btnPhoto: Button; private lateinit var btnRec: Button
    private lateinit var btnSwitch: Button; private lateinit var btnTorch: Button

    private var backId = ""; private var frontId = ""
    private var facing = CameraCharacteristics.LENS_FACING_BACK
    private var characteristics: CameraCharacteristics? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recordSurface: Surface? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var pendingStartRecorder = false
    private var recording = false
    private var torchOn = false
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.texture);   tvStatus = findViewById(R.id.status)
        tvShutter = findViewById(R.id.tvShutter);    tvIso = findViewById(R.id.tvIso)
        tvWb = findViewById(R.id.tvWb);               tvFocus = findViewById(R.id.tvFocus)
        tvZoom = findViewById(R.id.tvZoom)
        sbShutter = findViewById(R.id.sbShutter);    sbIso = findViewById(R.id.sbIso)
        sbWb = findViewById(R.id.sbWb);              sbFocus = findViewById(R.id.sbFocus)
        sbZoom = findViewById(R.id.sbZoom);          spFps = findViewById(R.id.spFps)
        btnPhoto = findViewById(R.id.btnPhoto);       btnRec = findViewById(R.id.btnRec)
        btnSwitch = findViewById(R.id.btnSwitch);     btnTorch = findViewById(R.id.btnTorch)

        bgThread = HandlerThread("camera").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                updateLabels()
                if (fromUser) applyManual()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        listOf(sbShutter, sbIso, sbWb, sbFocus, sbZoom).forEach { it.setOnSeekBarChangeListener(listener) }

        spFps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { applyManual() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        btnPhoto.setOnClickListener { takePhoto() }
        btnRec.setOnClickListener { if (recording) stopRecording() else startRecording() }
        btnSwitch.setOnClickListener { switchCamera() }
        btnTorch.setOnClickListener { torchOn = !torchOn; applyManual() }

        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            if (textureView.isAvailable) openCamera()
            else textureView.surfaceTextureListener = surfaceListener
        } else ActivityCompat.requestPermissions(this, perms, 1)
    }

    override fun onRequestPermissionsResult(rq: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rq, perms, res)
        if (rq == 1 && res.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (textureView.isAvailable) openCamera()
            else textureView.surfaceTextureListener = surfaceListener
        } else status("PERMISSION DENIED")
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (backId.isEmpty() && frontId.isEmpty()) {
            for (id in mgr.cameraIdList) {
                when (mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> backId = id
                    CameraCharacteristics.LENS_FACING_FRONT -> frontId = id
                }
            }
        }
        cameraId(if (facing == CameraCharacteristics.LENS_FACING_BACK && backId.isNotEmpty()) backId else frontId.ifEmpty { backId }, mgr)
    }

    private fun cameraId(id: String, mgr: CameraManager) {
        val ch = mgr.getCameraCharacteristics(id)
        characteristics = ch
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val largest = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width * it.height }!!
        imageReader?.close()
        imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 3)
        imageReader!!.setOnImageAvailableListener(onImageAvailable, bgHandler)
        configureUiForHardware()
        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    rebuildSession(withRecorder = false)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); if (cameraDevice == camera) cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null; status("CAMERA ERROR $error") }
            }, bgHandler)
        } catch (e: SecurityException) { status("NO CAMERA PERMISSION") }
    }

    private fun rebuildSession(withRecorder: Boolean) {
        val device = cameraDevice ?: return
        val ch = characteristics ?: return
        session?.close(); session = null
        val tex: SurfaceTexture = textureView.surfaceTexture ?: return
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val best = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= 1920 }
            .maxByOrNull { it.width * it.height }
            ?: map.getOutputSizes(SurfaceTexture::class.java).first()
        tex.setDefaultBufferSize(best.width, best.height)
        val previewSurface = Surface(tex)
        val b = device.createCaptureRequest(
            if (withRecorder) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(previewSurface)
        val targets = ArrayList<Surface>()
        targets.add(previewSurface)
        if (withRecorder) {
            b.addTarget(recordSurface!!)
            targets.add(recordSurface!!)
        }
        previewBuilder = b
        imageReader?.let { targets.add(it.surface) }

        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                setManual(previewBuilder!!)
                try { s.setRepeatingRequest(previewBuilder!!.build(), null, bgHandler) }
                catch (e: Exception) { status("REQ ERR: ${e.message}") }
                if (pendingStartRecorder) {
                    pendingStartRecorder = false
                    try { recorder?.start(); recording = true; status("RECORDING @ ${spFps.selectedItem}fps") }
                    catch (e: Exception) { status("REC START ERR: ${e.message}") }
                    runOnUiThread { btnRec.text = "STOP" }
                } else status("READY ${best.width}x${best.height}")
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { status("SESSION CONFIG FAILED") }
        }, bgHandler)
    }

    private fun setManual(b: CaptureRequest.Builder) {
        val ch = characteristics ?: return
        val hw = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

        if (hw != null && hw != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, SHUTTER_NS[sbShutter.progress])
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoOf(sbIso.progress))
        }
        if (ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AWB_MODE_OFF) == true) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, wbGains())
        }
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        if (minFocus != null && minFocus > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, (sbFocus.progress / 100f) * minFocus)
        }
        val zoom = 1f + sbZoom.progress / 100f * 7f
        val zr = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        if (zr != null && zoom <= zr.upper) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
            val maxZ = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            val z = zoom.coerceAtMost(maxZ)
            val w = (sensor.width() / z).toInt().coerceAtLeast(1)
            val h = (sensor.height() / z).toInt().coerceAtLeast(1)
            val l = (sensor.width() - w) / 2; val t = (sensor.height() - h) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + w, t + h))
        }
        val flashOk = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        b.set(CaptureRequest.FLASH_MODE,
            if (torchOn && flashOk) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)

        (spFps.selectedItem as? String)?.toIntOrNull()?.let { fps ->
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        }
    }

    private fun applyManual() {
        val b = previewBuilder ?: return
        val s = session ?: return
        setManual(b)
        try { s.setRepeatingRequest(b.build(), null, bgHandler) } catch (e: Exception) { }
    }

    private fun isoOf(p: Int): Int {
        val r = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 800)
        return (r.lower + (r.upper - r.lower) * (p / 100f)).toInt().coerceIn(r.lower, r.upper)
    }

    private fun wbGains(): ColorCorrectionGains {
        val t = sbWb.progress / 100f
        return ColorCorrectionGains(1f + (1f - t) * 0.8f, 1f, 1f + t * 1.2f, 1f)
    }

    private fun takePhoto() {
        val device = cameraDevice ?: return
        val s = session ?: return
        val reader = imageReader ?: return
        val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(reader.surface)
        b.set(CaptureRequest.JPEG_ORIENTATION, captureRotation())
        setManual(b)
        try { s.capture(b.build(), null, bgHandler); status("CAPTURED") }
        catch (e: Exception) { status("CAPTURE ERR: ${e.message}") }
    }

    private val onImageAvailable = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        image.close()
        saveJpeg(bytes)
    }

    private fun saveJpeg(bytes: ByteArray) {
        val name = "BM_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BM Cinema")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        runOnUiThread { status("SAVED: $name") }
    }

    private fun startRecording() {
        val ch = characteristics ?: return
        val fps = (spFps.selectedItem as? String)?.toIntOrNull() ?: 30
        try {
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val size = map.getOutputSizes(MediaRecorder::class.java)
                .filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width * it.height } ?: Size(1920, 1080)

            val r = MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setVideoEncodingBitRate(20_000_000)
            r.setVideoFrameRate(fps)
            r.setVideoSize(size.width, size.height)
            r.setOrientationHint(captureRotation())

            val name = "BM_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BM Cinema")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("cannot create video file")
            val pfd = contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("cannot open video file")
            r.setOutputFile(pfd.fileDescriptor)
            r.prepare()

            recorder = r
            recordSurface = r.surface
            pendingStartRecorder = true
            rebuildSession(withRecorder = true)
        } catch (e: Exception) {
            status("REC ERR: ${e.message}")
            recorder?.release(); recorder = null; recordSurface = null; pendingStartRecorder = false
        }
    }

    private fun stopRecording() {
        try { recorder?.stop() } catch (e: Exception) {}
        recorder?.release(); recorder = null; recordSurface = null
        recording = false
        btnRec.text = "REC"
        rebuildSession(withRecorder = false)
        status("CLIP SAVED to Movies/BM Cinema")
    }

    private fun captureRotation(): Int {
        val ch = characteristics ?: return 0
        val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val deg = when (windowManager.defaultDisplay.rotation) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT)
            (sensor - deg + 360) % 360 else (sensor + deg) % 360
    }

    private fun configureUiForHardware() {
        val ch = characteristics ?: return
        sbShutter.max = SHUTTER_LABEL.size - 1
        sbShutter.progress = 8
        sbIso.max = 100
        sbIso.progress = 35
        sbWb.max = 100; sbWb.progress = 46
        sbFocus.max = 100; sbFocus.progress = 0
        sbZoom.max = 100; sbZoom.progress = 0

        val hw = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        if (hw == null || hw == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY)
            tvIso.text = "n/a"
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        if (minFocus == null || minFocus <= 0f) tvFocus.text = "n/a"

        val fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf(Range(30, 30))
        val fixed = fpsRanges.filter { it.lower == it.upper }
            .map { it.upper }.filter { it in 15..120 }.distinct().sorted()
        spFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fixed.map { "$it" })
        spFps.setSelection((fixed.indexOf(30)).coerceAtLeast(0))
        updateLabels()
    }

    private fun updateLabels() {
        tvShutter.text = SHUTTER_LABEL[sbShutter.progress]
        val isoRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        tvIso.text = if (isoRange != null) isoOf(sbIso.progress).toString() else "n/a"
        tvWb.text = "${2500 + sbWb.progress * 65}K"
        tvFocus.text = "${sbFocus.progress}%"
        tvZoom.text = String.format(Locale.US, "%.1fx", 1f + sbZoom.progress / 100f * 7f)
    }

    private fun switchCamera() {
        if (frontId.isEmpty() && backId.isEmpty()) return
        facing = if (facing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        if (recording) {
            try { recorder?.stop(); recorder?.release() } catch (e: Exception) {}
            recorder = null; recordSurface = null; recording = false; btnRec.text = "REC"
        }
        session?.close(); session = null
        cameraDevice?.close(); cameraDevice = null
        openCamera()
    }

    private fun status(s: String) { runOnUiThread { tvStatus.text = s } }

    override fun onDestroy() {
        try { recorder?.stop(); recorder?.release() } catch (e: Exception) {}
        session?.close()
        cameraDevice?.close()
        imageReader?.close()
        bgThread?.quitSafely()
        super.onDestroy()
    }
}
