package com.example.photoviewer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.example.photoviewer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var exifToolManager: ExifToolManager
    
    private val photoPaths = mutableListOf<String>()
    private val selectedPaths = mutableSetOf<String>()
    private val albumMap = LinkedHashMap<String, MutableList<String>>()

    private var isUpdatingSelectAllCheckbox = false

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

        // 1. 初始化 3 列网格适配器
        binding.rvThumbnails.layoutManager = GridLayoutManager(this, 3)
        binding.rvThumbnails.adapter = ThumbnailAdapter(
            photos = photoPaths,
            selectedPaths = selectedPaths,
            exifToolManager = exifToolManager,
            onSelectionChanged = { updateSelectionUI() },
            onPhotoClick = { position -> openDetailPreview(position) }
        )

        // 2. 初始化大图模式下的 ViewPager2 适配器
        binding.viewPagerDetail.adapter = PhotoAdapter(photoPaths, exifToolManager)

        // 3. 大图模式下的旋转按钮
        binding.btnRotateSingle.setOnClickListener {
            val currentPos = binding.viewPagerDetail.currentItem
            val path = photoPaths.getOrNull(currentPos) ?: return@setOnClickListener
            exifToolManager.queueRotation(path)
            
            // 刷新大图与网格
            binding.viewPagerDetail.adapter?.notifyItemChanged(currentPos)
            binding.rvThumbnails.adapter?.notifyItemChanged(currentPos)
            binding.btnApply.text = "写入保存 EXIF (${exifToolManager.rotationQueue.size})"
        }

        // 4. 返回网格视图按钮
        binding.btnBackToGrid.setOnClickListener {
            closeDetailPreview()
        }

        // 5. 监听手机系统返回键：如果在看大图，返回键先退回网格
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.layoutDetailContainer.visibility == View.VISIBLE) {
                    closeDetailPreview()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // 全选复选框逻辑
        binding.cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSelectAllCheckbox) return@setOnCheckedChangeListener
            
            selectedPaths.clear()
            if (isChecked) {
                selectedPaths.addAll(photoPaths)
            }
            binding.rvThumbnails.adapter?.notifyDataSetChanged()
            updateSelectionUI()
        }

        // 旋转选中照片按钮
        binding.btnRotate.setOnClickListener {
            if (selectedPaths.isEmpty()) {
                Toast.makeText(this, "请先勾选需要旋转的照片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            for (path in selectedPaths) {
                exifToolManager.queueRotation(path)
            }

            binding.rvThumbnails.adapter?.notifyDataSetChanged()
            binding.btnApply.text = "写入保存 EXIF (${exifToolManager.rotationQueue.size})"
            Toast.makeText(this, "已为 ${selectedPaths.size} 张照片添加旋转指令", Toast.LENGTH_SHORT).show()
        }

        // 保存写入 EXIF 按钮
        binding.btnApply.setOnClickListener {
            if (exifToolManager.rotationQueue.isEmpty()) {
                Toast.makeText(this, "当前没有待保存的旋转修改", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

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

    private fun openDetailPreview(position: Int) {
        binding.layoutDetailContainer.visibility = View.VISIBLE
        binding.viewPagerDetail.setCurrentItem(position, false)
    }

    private fun closeDetailPreview() {
        binding.layoutDetailContainer.visibility = View.GONE
        binding.rvThumbnails.adapter?.notifyDataSetChanged()
    }

    private fun updateSelectionUI() {
        binding.tvSelectedCount.text = "已选中 ${selectedPaths.size} / ${photoPaths.size} 张"

        isUpdatingSelectAllCheckbox = true
        binding.cbSelectAll.isChecked = photoPaths.isNotEmpty() && selectedPaths.size == photoPaths.size
        isUpdatingSelectAllCheckbox = false
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
                    binding.btnApply.text = "写入保存 EXIF (${exifToolManager.rotationQueue.size})"
                    binding.rvThumbnails.adapter?.notifyDataSetChanged()
                    binding.viewPagerDetail.adapter?.notifyDataSetChanged()

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

    private fun handleSharedImages(): List<String> {
        val sharedPaths = mutableListOf<String>()
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) listOf(uri) else emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            }
            else -> emptyList()
        }

        for (uri in uris) {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    if (pathIndex != -1) {
                        val path = cursor.getString(pathIndex)
                        if (!path.isNullOrEmpty()) {
                            sharedPaths.add(path)
                        }
                    }
                }
            }
        }
        return sharedPaths
    }

    private fun loadAlbumsAndPhotos() {
        albumMap.clear()

        val sharedPhotos = handleSharedImages()
        if (sharedPhotos.isNotEmpty()) {
            albumMap["分享的照片"] = sharedPhotos.toMutableList()
        }

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

        val defaultPosition = if (sharedPhotos.isNotEmpty()) albumNames.indexOf("分享的照片") else 0

        binding.spinnerAlbums.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedAlbum = albumNames[position]
                photoPaths.clear()
                selectedPaths.clear()
                
                albumMap[selectedAlbum]?.let { photoPaths.addAll(it) }
                
                binding.rvThumbnails.adapter?.notifyDataSetChanged()
                binding.viewPagerDetail.adapter?.notifyDataSetChanged()
                updateSelectionUI()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (defaultPosition >= 0) {
            binding.spinnerAlbums.setSelection(defaultPosition)
        }
    }
}