package com.example.tiktokvideopatcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layoثut.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mp4parser.IsoFile
import org.mp4parser.boxes.iso14496.part12.MediaHeaderBox
import org.mp4parser.boxes.iso14496.part12.MovieHeaderBox
import org.mp4parser.boxes.iso14496.part12.TrackBox
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels

import com.example.tiktokvideopatcher.ui.theme.TikTokVideoPatcherTheme

class MainActivity : ComponentActivity() {

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                takePersistableUriPermission(uri)
                selectedUri = uri
            }
        }
    }

    private var selectedUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TikTokVideoPatcherTheme {
                MainScreen(
                    onSelectVideo = { pickVideoLauncher.launch(createVideoPickerIntent()) },
                    onAnalyzeVideo = { analyzeSelectedVideo() },
                    onPatchVideo = { patchSelectedVideo(it) },
                    selectedUri = selectedUri
                )
            }
        }
    }

    private fun createVideoPickerIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/mp4"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/mp4"))
        }
    }

    private fun takePersistableUriPermission(uri: Uri) {
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: SecurityException) {
            Log.w("MP4_PATCHER", "Failed to take persistable URI permission", e)
        }
    }

    private fun analyzeSelectedVideo() {
        selectedUri?.let { uri ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val filePath = getFilePathFromUri(uri)
                    if (filePath != null) {
                        analyzeMp4Timescale(filePath)
                    } else {
                        Log.e("MP4_PATCHER", "Could not get file path from URI")
                    }
                } catch (e: Exception) {
                    Log.e("MP4_PATCHER", "Error analyzing video", e)
                }
            }
        } ?: Log.w("MP4_PATCHER", "No video selected for analysis")
    }

    private fun patchSelectedVideo(snackbarHostState: SnackbarHostState) {
        selectedUri?.let { uri ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val inputPath = getFilePathFromUri(uri)
                    if (inputPath == null) {
                        showSnackbar(snackbarHostState, "Error: Could not get file path")
                        return@launch
                    }

                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val outputFile = File(downloadsDir, "patched_${System.currentTimeMillis()}.mp4")
                    val outputPath = outputFile.absolutePath

                    val success = patchMp4Timescale(inputPath, outputPath)
                    
                    if (success) {
                        showSnackbar(snackbarHostState, "Success! Patched video saved to: ${outputFile.name}")
                        Log.i("MP4_PATCHER", "Patched video saved to: $outputPath")
                    } else {
                        showSnackbar(snackbarHostState, "Failed to patch video")
                    }
                } catch (e: Exception) {
                    Log.e("MP4_PATCHER", "Error patching video", e)
                    showSnackbar(snackbarHostState, "Error patching video: ${e.message}")
                }
            }
        } ?: showSnackbar(snackbarHostState, "Please select a video first")
    }

    private fun showSnackbar(snackbarHostState: SnackbarHostState, message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    private fun getFilePathFromUri(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val cacheFile = File(cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
            inputStream?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            inputStream?.close()
            cacheFile.absolutePath
        } catch (e: Exception) {
            Log.e("MP4_PATCHER", "Error copying video to cache", e)
            null
        }
    }

    private fun analyzeMp4Timescale(filePath: String) {
        Log.d("MP4_PATCHER", "Starting MP4 analysis for: $filePath")

        try {
            val randomAccessFile = RandomAccessFile(filePath, "r")
            val isoFile = IsoFile(randomAccessFile.channel)

            val movieHeaderBox = findMovieHeaderBox(isoFile)

            if (movieHeaderBox != null) {
                val timescale = movieHeaderBox.timescale
                val duration = movieHeaderBox.duration
                
                Log.i("MP4_PATCHER", "========================================")
                Log.i("MP4_PATCHER", "MP4 Analysis Result:")
                Log.i("MP4_PATCHER", "File: $filePath")
                Log.i("MP4_PATCHER", "MovieHeaderBox (mvhd) found!")
                Log.i("MP4_PATCHER", "Timescale: $timescale")
                Log.i("MP4_PATCHER", "Duration: $duration")
                Log.i("MP4_PATCHER", "Calculated Duration (seconds): ${duration.toDouble() / timescale}")
                Log.i("MP4_PATCHER", "========================================")
                
                analyzeMediaHeaderBoxes(isoFile)
                logBoxStructure(isoFile, 0)
            } else {
                Log.e("MP4_PATCHER", "MovieHeaderBox (mvhd) NOT found in the MP4 file")
                logBoxStructure(isoFile, 0)
            }

            isoFile.close()
            randomAccessFile.close()

        } catch (e: Exception) {
            Log.e("MP4_PATCHER", "Failed to analyze MP4 file: ${e.message}", e)
        }
    }

    private fun analyzeMediaHeaderBoxes(isoFile: IsoFile) {
        val trackBoxes = findAllTrackBoxes(isoFile)
        for (trackBox in trackBoxes) {
            val mdhd = findMediaHeaderBox(trackBox)
            if (mdhd != null) {
                val handlerType = findHandlerType(trackBox)
                Log.i("MP4_PATCHER", "Track: handler=$handlerType, mdhd timescale=${mdhd.timescale}, duration=${mdhd.duration}")
            }
        }
    }

    private fun findAllTrackBoxes(isoFile: IsoFile): MutableList<TrackBox> {
        val trackBoxes = mutableListOf<TrackBox>()
        for (box in isoFile.boxes) {
            findTrackBoxesRecursive(box, trackBoxes)
        }
        return trackBoxes
    }

    private fun findTrackBoxesRecursive(box: org.mp4parser.Box, trackBoxes: MutableList<TrackBox>) {
        if (box is TrackBox) {
            trackBoxes.add(box)
        }
        if (box is org.mp4parser.Container) {
            for (childBox in box.boxes) {
                findTrackBoxesRecursive(childBox, trackBoxes)
            }
        }
    }

    private fun findMediaHeaderBox(trackBox: TrackBox): MediaHeaderBox? {
        val mediaBox = trackBox.boxes.firstOrNull { it.type == "mdia" } as? org.mp4parser.Container
        if (mediaBox != null) {
            return mediaBox.boxes.firstOrNull { it is MediaHeaderBox } as? MediaHeaderBox
        }
        return null
    }

    private fun findHandlerType(trackBox: TrackBox): String {
        val mediaBox = trackBox.boxes.firstOrNull { it.type == "mdia" } as? org.mp4parser.Container
        if (mediaBox != null) {
            val handlerBox = mediaBox.boxes.firstOrNull { it.type == "hdlr" }
            if (handlerBox != null) {
                try {
                    val field = handlerBox.javaClass.getDeclaredField("handlerType")
                    field.isAccessible = true
                    return field.get(handlerBox) as String
                } catch (e: Exception) {
                    return "unknown"
                }
            }
        }
        return "unknown"
    }

    /**
     * Core patching function - multiplies timescale by 4 in mvhd and video track mdhd boxes
     */
    private fun patchMp4Timescale(inputPath: String, outputPath: String): Boolean {
        Log.d("MP4_PATCHER", "Starting MP4 patching: $inputPath -> $outputPath")

        try {
            val randomAccessFile = RandomAccessFile(inputPath, "r")
            val isoFile = IsoFile(randomAccessFile.channel)

            // 1. Patch MovieHeaderBox (mvhd) - multiply timescale by 4
            val movieHeaderBox = findMovieHeaderBox(isoFile)
            if (movieHeaderBox != null) {
                val originalTimescale = movieHeaderBox.timescale
                val newTimescale = originalTimescale * 4
                movieHeaderBox.timescale = newTimescale
                Log.i("MP4_PATCHER", "Patched mvhd: timescale $originalTimescale -> $newTimescale")
            } else {
                Log.e("MP4_PATCHER", "MovieHeaderBox (mvhd) NOT found!")
                isoFile.close()
                randomAccessFile.close()
                return false
            }

            // 2. Patch MediaHeaderBoxes (mdhd) in video tracks only - multiply timescale by 4
            val trackBoxes = findAllTrackBoxes(isoFile)
            var patchedMdhdCount = 0
            for (trackBox in trackBoxes) {
                val handlerType = findHandlerType(trackBox)
                if (handlerType == "vide") {
                    val mdhd = findMediaHeaderBox(trackBox)
                    if (mdhd != null) {
                        val originalTimescale = mdhd.timescale
                        val newTimescale = originalTimescale * 4
                        mdhd.timescale = newTimescale
                        patchedMdhdCount++
                        Log.i("MP4_PATCHER", "Patched mdhd (track: $handlerType): timescale $originalTimescale -> $newTimescale")
                    }
                }
            }
            Log.i("MP4_PATCHER", "Patched $patchedMdhdCount MediaHeaderBox(es)")

            // 3. Write the modified MP4 to output file
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()
            
            val fileOutputStream = FileOutputStream(outputFile)
            val writableChannel = fileOutputStream.channel
            
            // Write the modified ISO file
            isoFile.getBox(writableChannel)
            
            writableChannel.close()
            fileOutputStream.close()
            isoFile.close()
            randomAccessFile.close()

            Log.i("MP4_PATCHER", "========================================")
            Log.i("MP4_PATCHER", "MP4 Patching Complete!")
            Log.i("MP4_PATCHER", "Output: $outputPath")
            Log.i("MP4_PATCHER", "========================================")
            
            return true

        } catch (e: Exception) {
            Log.e("MP4_PATCHER", "Failed to patch MP4 file: ${e.message}", e)
            return false
        }
    }

    private fun findMovieHeaderBox(isoFile: IsoFile): MovieHeaderBox? {
        val boxes = isoFile.boxes
        for (box in boxes) {
            val mvhd = findMovieHeaderBoxRecursive(box)
            if (mvhd != null) {
                return mvhd
            }
        }
        return null
    }

    private fun findMovieHeaderBoxRecursive(box: org.mp4parser.Box): MovieHeaderBox? {
        if (box is MovieHeaderBox) {
            return box
        }
        if (box is org.mp4parser.Container) {
            for (childBox in box.boxes) {
                val result = findMovieHeaderBoxRecursive(childBox)
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }

    private fun logBoxStructure(isoFile: IsoFile, indent: Int) {
        for (box in isoFile.boxes) {
            logBoxRecursive(box, indent)
        }
    }

    private fun logBoxRecursive(box: org.mp4parser.Box, indent: Int) {
        val prefix = "  ".repeat(indent)
        val boxType = box.type
        val boxSize = box.size
        Log.d("MP4_PATCHER", "$prefix[$boxType] size=$boxSize")
        
        if (box is org.mp4parser.Container) {
            for (childBox in box.boxes) {
                logBoxRecursive(childBox, indent + 1)
            }
        }
    }
}

@Composable
fun MainScreen(
    onSelectVideo: () -> Unit,
    onAnalyzeVideo: () -> Unit,
    onPatchVideo: (SnackbarHostState) -> Unit,
    selectedUri: Uri?
) {
    var statusText by remember { mutableStateOf("No video selected") }
    var isAnalyzing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SnackbarHost(hostState = snackbarHostState)
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            elevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "TikTok Video Patcher",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Analyze MP4 mvhd Timescale",
                    fontSize = 16.sp
                )

                Text(
                    text = if (selectedUri != null) {
                        "Selected: ${selectedUri.lastPathSegment ?: "video.mp4"}"
                    } else {
                        "No video selected"
                    },
                    fontSize = 14.sp
                )

                Text(
                    text = statusText,
                    fontSize = 14.sp
                )

                Button(
                    onClick = {
                        onSelectVideo()
                        statusText = "Video selected. Ready to analyze."
                    },
                    modifier = Modifier.size(200.dp, 56.dp)
                ) {
                    Text(text = "Select Video", fontSize = 18.sp)
                }

                Button(
                    onClick = {
                        if (selectedUri != null) {
                            isAnalyzing = true
                            statusText = "Analyzing..."
                            onAnalyzeVideo()
                            statusText = "Analysis complete. Check Logcat (tag: MP4_PATCHER)"
                            isAnalyzing = false
                        } else {
                            statusText = "Please select a video first"
                        }
                    },
                    enabled = selectedUri != null && !isAnalyzing,
                    modifier = Modifier.size(200.dp, 56.dp)
                ) {
                    Text(
                        text = if (isAnalyzing) "Analyzing..." else "Analyze Video",
                        fontSize = 18.sp
                    )
                }

                Button(
                    onClick = {
                        onPatchVideo(snackbarHostState)
                    },
                    enabled = selectedUri != null && !isAnalyzing,
                    modifier = Modifier.size(200.dp, 56.dp)
                ) {
                    Text(text = "Patch Video", fontSize = 18.sp)
                }

                Text(
                    text = "This tool extracts the 'mvhd' timescale from MP4 files.\nCheck Logcat with filter 'MP4_PATCHER' for results.",
                    fontSize = 12.sp
                )
            }
        }
    }
}
