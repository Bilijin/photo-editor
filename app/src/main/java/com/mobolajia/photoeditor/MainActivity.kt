package com.mobolajia.photoeditor

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.get
import androidx.core.graphics.set
import com.mobolajia.photoeditor.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import kotlin.math.pow


class MainActivity : AppCompatActivity() {
    private lateinit var bitmap: Bitmap
    private lateinit var binding: ActivityMainBinding
    private lateinit var tempBitmap: Bitmap
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindViews()

        bitmap = getBitmapImg()!!
        tempBitmap = bitmap
        binding.ivPhoto.setImageBitmap(bitmap)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            binding.btnSave.callOnClick()
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun bindViews() {
        setupSliders()

        val activityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                val photoUri = it.data?.data ?: return@registerForActivityResult
                bitmap = loadBitmap(photoUri).copy(Bitmap.Config.RGB_565, true)
                val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

                bitmap = Bitmap.createScaledBitmap(bitmap, 200, (200 / aspectRatio).toInt(), true)
                binding.ivPhoto.setImageBitmap(bitmap)
                binding.slBrightness.value = 0f

            }
        }

        binding.btnGallery.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            activityResultLauncher.launch(intent)
        }

        binding.btnSave.setOnClickListener {
            if (checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                val vals = ContentValues()
                vals.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                vals.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                vals.put(MediaStore.Images.ImageColumns.WIDTH, tempBitmap.width)
                vals.put(MediaStore.Images.ImageColumns.HEIGHT, tempBitmap.height)

                val uri = this@MainActivity.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals
                ) ?: return@setOnClickListener

                contentResolver.openOutputStream(uri).use {
                    if (it != null) {
                        tempBitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                    }
                }

            } else {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0
                )
            }
        }
    }

    private fun setupSliders() {
        binding.slBrightness.apply {
            value = 0f
            stepSize = 10f
            valueTo = 250f
            valueFrom = -250f
        }

        binding.slContrast.apply {
            value = 0f
            stepSize = 10f
            valueTo = 250f
            valueFrom = -250f
        }

        binding.slSaturation.apply {
            value = 0f
            stepSize = 10f
            valueTo = 250f
            valueFrom = -250f
        }

        binding.slGamma.apply {
            value = 1f
            stepSize = 0.2f
            valueTo = 4f
            valueFrom = 0.2f
        }

        binding.slBrightness.addOnChangeListener { _, value, _ ->
            val contrast = binding.slContrast.value.toInt()
            val saturation = binding.slSaturation.value.toInt()
            val gamma = binding.slGamma.value.toDouble()
            applyFilters(value.toInt(), contrast, saturation, gamma)


        }

        binding.slContrast.addOnChangeListener { _, value, _ ->
            val brightness = binding.slBrightness.value.toInt()
            val saturation = binding.slSaturation.value.toInt()
            val gamma = binding.slGamma.value.toDouble()
            applyFilters(
                brightness,
                value.toInt(),
                saturation,
                gamma
            )

        }

        binding.slSaturation.addOnChangeListener { _, value, _ ->
            val brightness = binding.slBrightness.value.toInt()
            val contrast = binding.slContrast.value.toInt()
            val gamma = binding.slGamma.value.toDouble()
            applyFilters(brightness, contrast, value.toInt(), gamma)
        }

        binding.slGamma.addOnChangeListener { _, value, _ ->
            val brightness = binding.slBrightness.value.toInt()
            val contrast = binding.slContrast.value.toInt()
            val saturation = binding.slSaturation.value.toInt()
            applyFilters(brightness, contrast, saturation, value.toDouble())
        }
    }

    private fun applyFilters(brightness: Int, contrast: Int, saturation: Int, gamma: Double) {
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch(Dispatchers.Default) {
            tempBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)

            val deferredBitmap = this.async {
                var totalBrightness : Long = 0

                for (x in 0 until tempBitmap.width) {
                    for (y in 0 until tempBitmap.height) {
                        val color = bitmap[x, y]
                        val (brightnessA, brightnessB) = getPixelColorForBrightness(brightness, color)

                        tempBitmap[x, y] = brightnessA
                        totalBrightness += brightnessB
                    }
                }

                val avgBrightness = (totalBrightness / (tempBitmap.width * tempBitmap.height)).toInt()
                val contrastAlpha: Double = (255.0 + contrast) / (255.0 - contrast)
                val saturationAlpha: Double = (255.0 + saturation) / (255.0 - saturation)

                for (x in 0 until tempBitmap.width) {
                    for (y in 0 until tempBitmap.height) {
                        val color = tempBitmap[x, y]
                        val colorPlusContrast = getPixelColorForContrast(avgBrightness, contrastAlpha, color)
                        val colorPlusSaturation = getPixelColorForSaturation(saturationAlpha, colorPlusContrast)
                        val colorPlusGamma = getPixelColorForGamma(gamma, colorPlusSaturation)
                        tempBitmap[x, y] = colorPlusGamma
                    }
                }
            }

            deferredBitmap.await()
            deferredBitmap.join()

            ensureActive()
            runOnUiThread {
                binding.ivPhoto.setImageBitmap(tempBitmap)
            }
        }
    }

    private fun getPixelColorForBrightness(brightness: Int, col: Int): Pair<Int, Int> {
        val red = (Color.red(col) + brightness).coerceIn(0, 255)
        val green = (Color.green(col) + brightness).coerceIn(0, 255)
        val blue = (Color.blue(col) + brightness).coerceIn(0, 255)
        return Pair(Color.rgb(red, green, blue), (red + green + blue) / 3)
    }

    private fun getPixelColorForContrast(avgBrightness: Int, alpha: Double, col: Int): Int {
        val red =
            (alpha * (Color.red(col) - avgBrightness) + avgBrightness).toInt().coerceIn(0, 255)
        val green =
            (alpha * (Color.green(col) - avgBrightness) + avgBrightness).toInt().coerceIn(0, 255)
        val blue =
            (alpha * (Color.blue(col) - avgBrightness) + avgBrightness).toInt().coerceIn(0, 255)

        return Color.rgb(red, green, blue)
    }

    private fun getPixelColorForSaturation(alpha: Double, col: Int): Int {
        var red = Color.red(col)
        var green = Color.green(col)
        var blue = Color.blue(col)
        val avgRgb = (red + green + blue) / 3

        red = (alpha * (red - avgRgb) + avgRgb).toInt().coerceIn(0, 255)
        green = (alpha * (green - avgRgb) + avgRgb).toInt().coerceIn(0, 255)
        blue = (alpha * (blue - avgRgb) + avgRgb).toInt().coerceIn(0, 255)

        return Color.rgb(red, green, blue)
    }

    private fun getPixelColorForGamma(gamma: Double, col: Int): Int {
        var red = Color.red(col)
        var green = Color.green(col)
        var blue = Color.blue(col)

        red = (255.0 * (red / 255.0).pow(gamma)).toInt().coerceIn(0, 255)
        green = (255.0 * (green / 255.0).pow(gamma)).toInt().coerceIn(0, 255)
        blue = (255.0 * (blue / 255.0).pow(gamma)).toInt().coerceIn(0, 255)

        return Color.rgb(red, green, blue)
    }

    private fun loadBitmap(photoUri: Uri) =
        MediaStore.Images.Media.getBitmap(this.contentResolver, photoUri)

    fun getBitmapImg(): Bitmap? {
        val assetManager = this.assets
        val istr: InputStream
        var bitmap: Bitmap? = null
        try {
            istr = assetManager.open("base_img.jpg")
            bitmap = BitmapFactory.decodeStream(istr)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return bitmap
    }

    private fun checkPermission(permission: String): Boolean {
        return this.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}