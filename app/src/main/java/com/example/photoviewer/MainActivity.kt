package com.example.photoviewer

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.photoviewer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var exifToolManager: ExifToolManager
    private val photoPaths = mutableListOf<String>()

    // 相册分类映射：KEY 为相册名称，VALUE 为该相册下的图片路径列表
    private val albumMap = LinkedHashMap<String, MutableList<String>>()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val isGranted = permissions.entries.any { it.value }
        if (isGranted) {
            loadAlbumsAndPhotos()
        } else {
            Toast.makeText(this, "需要访问相册权限才能查看和修改照片", Toast.LENGTH_LONG).show()
        }
    }

    private val writeRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startBatchExifProcess()
        } else {
            Toast.makeText(this, "修改已取消：需要获得授权才能写入照片标头", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        exifToolManager = ExifToolManager(this)
        binding.viewPager.adapter = PhotoAdapter(photoPaths, exifToolManager)

        binding.btnRotate.setOnClickListener {
            val currentPath = photoPaths.getOrNull(binding.viewPager.currentItem) ?: return@setOnClickListener
            exifToolManager.queueRotation(currentPath)
            binding.viewPager.adapter?.notifyItemChanged(binding.viewPager.currentItem)
            binding.btnApply.text = "应用旋转 (${exifToolManager.rotationQueue.size})"
        }

        binding.btnApply.setOnClickListener {
            if (exifToolManager.rotationQueue.isEmpty()) return@setOnClickListener

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val urisToModify = exifToolManager.getPendingUris()
                if (urisToModify.isNotEmpty()) {
                    val pendingIntent = MediaStore.createWriteRequest(contentResolver, urisToModify)
                    val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    writeRequestLauncher.launch(intentSenderRequest)
                } else {
                    startBatchExifProcess()
                }
            } else {
                startBatchExifProcess()
            }
        }

        checkAndRequestPermissions()
    }

    private fun startBatchExifProcess() {
        exifToolManager.applyBatchRotations(
            onProgress = { completed, total ->
                runOnUiThread {
                    binding.btnApply.text = "写入中 ($completed/$total)"
                }
            },
            onComplete = { success, fail ->
                runOnUiThread {
                    binding.btnApply.text = "应用旋转 (${exifToolManager.rotationQueue.size})"
                    binding.viewPager.adapter?.notifyDataSetChanged()

                    if (fail == 0) {
                        Toast.makeText(this, "成功将 EXIF 标头写入 $success 张图片！", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "成功: $success, 失败: $fail", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            }
            else -> {
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            }
        }

        requestPermission.launch(permissionsToRequest)
    }

    private fun loadAlbumsAndPhotos() {
        albumMap.clear()

        val allPhotosList = mutableListOf<String>()
        albumMap["全部照片"] = allPhotosList

        val projection = arrayOf(
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )

        cursor?.use {
            val pathCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val bucketCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (it.moveToNext()) {
                val path = it.getString(pathCol) ?: continue
                val albumName = it.getString(bucketCol) ?: "其他"

                allPhotosList.add(path)

                if (!albumMap.containsKey(albumName)) {
                    albumMap[albumName] = mutableListOf()
                }
                albumMap[albumName]?.add(path)
            }
        }

        val albumNames = albumMap.keys.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, albumNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAlbums.adapter = adapter

        binding.spinnerAlbums.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedAlbum = albumNames[position]
                photoPaths.clear()
                albumMap[selectedAlbum]?.let { photoPaths.addAll(it) }
                binding.viewPager.adapter?.notifyDataSetChanged()
                binding.viewPager.setCurrentItem(0, false)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}
