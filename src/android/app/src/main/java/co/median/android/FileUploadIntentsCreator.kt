package co.median.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Parcelable
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import co.median.median_core.AppConfig
import co.median.median_core.Utils
import co.median.median_core.dto.CaptureQuality
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class FileUploadIntentsCreator(val context: Context) {
    private val isAndroid10orAbove = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private val canSaveToPublicStorageAndroid9orBelow = Utils.hasManifestPermission(context as Activity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    private val mimeTypes = hashSetOf<String>()
    private val appConfig = AppConfig.getInstance(context)
    private var packageManger = context.packageManager
    private var mimeTypeSpecs: Array<String>? = null
    private var multiple: Boolean = false

    var currentCaptureUri: Uri? = null
    var currentVideoRecordingUri: Uri? = null
    var forceSaveToInternalStorage = false

    fun setUploadSpecs(mimeTypeSpecs: Array<String>, multiple: Boolean) {
        this.mimeTypeSpecs = mimeTypeSpecs
        this.multiple = multiple
        extractMimeTypes()
    }

    private fun extractMimeTypes() {
        mimeTypeSpecs?.forEach { spec ->
            val specParts = spec.split("[,;\\s]")
            specParts.forEach {
                if (it.startsWith(".")) {
                    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.substring(1))
                    mimeType?.let { it1 -> mimeTypes.add(it1) }
                } else if (it.contains("/")) {
                    mimeTypes.add(it)
                }
            }
        }

        if (mimeTypes.isEmpty()) {
            mimeTypes.add("*/*")
        }
    }

    private fun imagesAllowed(): Boolean {
        if (!Utils.isPermissionGranted(context as Activity, android.Manifest.permission.CAMERA)) return false
        return canUploadImage();
    }

    private fun videosAllowed(): Boolean {
        if (!Utils.isPermissionGranted(context as Activity, android.Manifest.permission.CAMERA)) return false
        return canUploadVideo()
    }

    fun canUploadImage(): Boolean {
        return mimeTypes.contains("*/*") || mimeTypes.any { it.contains("image/") }
    }

    fun canUploadVideo(): Boolean {
        return mimeTypes.contains("*/*") || mimeTypes.any { it.contains("video/") }
    }

    private fun photoCameraIntents(): ArrayList<Intent> {
        val intents = arrayListOf<Intent>()

        if (!appConfig.directCameraUploads) {
            return intents
        }

        val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        applyPhotoCameraSettings(captureIntent)

        val resolveList: List<ResolveInfo> = listOfAvailableAppsForIntent(captureIntent)
        for (resolve in resolveList) {
            val packageName = resolve.activityInfo.packageName
            val intent = Intent(captureIntent)
            intent.component = ComponentName(resolve.activityInfo.packageName, resolve.activityInfo.name)
            intent.setPackage(packageName)
            intents.add(intent)
        }

        return intents
    }

    private fun applyPhotoCameraSettings(captureIntent: Intent) {
        if (appConfig.cameraConfig.saveToGallery && (isAndroid10orAbove || canSaveToPublicStorageAndroid9orBelow) && !forceSaveToInternalStorage) {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFileName = "IMG_$timeStamp.jpg"

            // Saving the media files to DCIM/CAMERA will automatically show the media to the Gallery
            currentCaptureUri = if (isAndroid10orAbove) {
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, imageFileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                })
            } else {
                val cameraDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera").apply { mkdirs() }
                val outputFile = File(cameraDir, imageFileName)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outputFile)
            }
        } else {
            // Save to internal app storage
            currentCaptureUri = createTempOutputUri()
        }

        captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, currentCaptureUri)
    }

    private fun videoCameraIntents(): ArrayList<Intent> {
        val intents = arrayListOf<Intent>()

        if (!appConfig.directCameraUploads) {
            return intents
        }

        val captureIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        applyVideoCameraSettings(captureIntent)

        val resolveList: List<ResolveInfo> = listOfAvailableAppsForIntent(captureIntent)
        for (resolve in resolveList) {
            val packageName = resolve.activityInfo.packageName
            val intent = Intent(captureIntent)
            intent.component = ComponentName(resolve.activityInfo.packageName, resolve.activityInfo.name)
            intent.setPackage(packageName)

            intents.add(intent)
        }

        return intents
    }

    private fun applyVideoCameraSettings(videoIntent: Intent) {
        val cameraConfig = appConfig.cameraConfig

        if (cameraConfig.captureQuality == CaptureQuality.HIGH) {
            videoIntent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
        } else {
            videoIntent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
        }

        // Note: Saving media file to DCIM/Camera and will automatically appear in Gallery

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val videoFileName = "VID_$timeStamp.mp4"

        // For Android 10 and above
        if (cameraConfig.saveToGallery && isAndroid10orAbove && !forceSaveToInternalStorage) {
            this.currentVideoRecordingUri =  context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, videoFileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            })
            videoIntent.putExtra(MediaStore.EXTRA_OUTPUT, this.currentVideoRecordingUri)
            return
        }

        // For devices Android 9 and lower, must check permission to write to storage and request permission
        // Otherwise, save to internal app storage.
        if (cameraConfig.saveToGallery && canSaveToPublicStorageAndroid9orBelow && !forceSaveToInternalStorage) {
            val cameraDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera").apply { mkdirs() }
            val outputFile = File(cameraDir, videoFileName)
            this.currentVideoRecordingUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outputFile)
            videoIntent.putExtra(MediaStore.EXTRA_OUTPUT, this.currentVideoRecordingUri)
            return
        }

        // Default: Save to internal app storage
        currentVideoRecordingUri = createTempOutputUri(isVideo = true)
        videoIntent.putExtra(MediaStore.EXTRA_OUTPUT, currentVideoRecordingUri)
    }

    private fun createTempOutputUri(isVideo: Boolean = false): Uri {
        // Note: Only one file instance should exist for temporary files to optimize memory usage.
        // The file should not be deleted immediately as the page may use it indefinitely.
        val fileName = if (isVideo) "temp_video_recording.mp4" else "temp_capture_image.jpg"

        // Save file as cache, should be in "downloads" folder as defined in filepaths.xml
        val downloadsDir = File(context.cacheDir, "downloads")
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()  // Create the directory if it doesn't exist
        }
        val file = File(downloadsDir, fileName)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.applicationContext.packageName}.fileprovider",
            file
        )

        return uri
    }

    /**
     * Checks whether storage permission should be requested.
     * This method is intended for Android 9 or lower, where the app:
     * - Has `WRITE_EXTERNAL_STORAGE` permission in the Manifest. (canSaveToPublicStorageAndroid9orBelow)
     * - Has `CameraConfig.saveToGallery` enabled.
     *
     * @return `true` if storage permission should be requested, otherwise `false`.
     */
    fun needsStoragePermissionAndroid9(): Boolean {
        return !isAndroid10orAbove && canSaveToPublicStorageAndroid9orBelow && appConfig.cameraConfig.saveToGallery
    }

    fun deleteUriFiles() {
        this.currentCaptureUri?.let {
            context.contentResolver.delete(it, null, null)
            this.currentCaptureUri = null
        }

        this.currentVideoRecordingUri?.let {
            context.contentResolver.delete(it, null, null)
            this.currentVideoRecordingUri = null
        }
    }

    private fun filePickerIntent(): Intent {
        var intent: Intent
        intent = Intent(Intent.ACTION_GET_CONTENT) // or ACTION_OPEN_DOCUMENT
        intent.type = mimeTypes.joinToString(", ")
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        val resolveList: List<ResolveInfo> = listOfAvailableAppsForIntent(intent)

        if (resolveList.isEmpty() && Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            intent = Intent("com.sec.android.app.myfiles.PICK_DATA")
            intent.putExtra("CONTENT_TYPE", "*/*")
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            return intent
        }

        return intent
    }

    fun cameraIntent(): Intent {
        val mediaIntents = if (imagesAllowed()) {
            photoCameraIntents()
        } else {
            videoCameraIntents()
        }
        return mediaIntents.first()
    }

    @SuppressLint("IntentReset")
    fun chooserIntent(): Intent {
        val directCaptureIntents = arrayListOf<Intent>()
        if (imagesAllowed()) {
            directCaptureIntents.addAll(photoCameraIntents())
        }
        if (videosAllowed()) {
            directCaptureIntents.addAll(videoCameraIntents())
        }

        val chooserIntent: Intent?
        val mediaIntent: Intent?

        if (imagesAllowed() xor videosAllowed()) {
            mediaIntent = getMediaInitialIntent()
            mediaIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            chooserIntent = Intent.createChooser(mediaIntent, context.getString(R.string.choose_action))
        } else if (onlyImagesAndVideo() && !isGooglePhotosDefaultApp()) {
            mediaIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            mediaIntent.type = "image/*, video/*"
            mediaIntent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            mediaIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            chooserIntent = Intent.createChooser(mediaIntent, context.getString(R.string.choose_action))
        } else {
            chooserIntent = Intent.createChooser(filePickerIntent(), context.getString(R.string.choose_action))
        }
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, directCaptureIntents.toTypedArray<Parcelable>())

        return chooserIntent
    }

    private fun getMediaInitialIntent(): Intent {
        return if (imagesAllowed()) {
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        } else {
            Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        }
    }

    private fun onlyImagesAndVideo(): Boolean {
        return mimeTypes.all { it.startsWith("image/") || it.startsWith("video/") }
    }

    private fun isGooglePhotosDefaultApp(): Boolean {
        val captureIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        val resolveList: List<ResolveInfo> = listOfAvailableAppsForIntent(captureIntent)

        return resolveList.size == 1 && resolveList.first().activityInfo.packageName == "com.google.android.apps.photos"
    }

    private fun listOfAvailableAppsForIntent(intent: Intent): List<ResolveInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManger.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManger.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }
}