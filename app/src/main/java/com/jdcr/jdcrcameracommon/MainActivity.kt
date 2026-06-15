package com.jdcr.jdcrcameracommon

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jdcr.jdcrcamerabase.JdcrCameraHelper
import com.jdcr.jdcrcamerabase.JdcrCustomPreviewView
import com.jdcr.jdcrcamerabase.config.JdcrCameraPreviewConfig
import com.jdcr.jdcrcamerabase.config.JdcrCameraStartConfig
import com.jdcr.jdcrcamerabase.util.JdcrCameraLog
import com.jdcr.jdcrcameracommon.ui.theme.JdcrCameraCommonTheme
import com.jdcr.jdcrcameragesture.JdcrGestureRecognizerHelper
import com.jdcr.jdcrlog.JdcrLog
import com.jdcr.jdcrlog.JdcrLogBase
import com.jdcr.jdcrqrcode.JdcrQRCodeHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        JdcrCameraLog.enable(true)
        setContent {
            JdcrCameraCommonTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val previewView = remember { JdcrCustomPreviewView(context, JdcrCameraPreviewConfig.getTest()) }
    val helper = JdcrCameraHelper(context, lifecycleOwner, previewView)

    LaunchedEffect(Unit) {
//        return@LaunchedEffect
        delay(500)
        val modelAssetPath = "mediapipe/model/gesture_recognizer.task"
        val option = JdcrGestureRecognizerHelper.Builder()
            .setModelAssetPath(modelAssetPath)
            .build()
        val recognizer = JdcrGestureRecognizerHelper(context, option)
//        helper.apply {
//            getImageAnalysisBitmapFlow().collect {
//                recognizer.recognizeAsyncBitmap(it)
//            }
//        }
    }

    LaunchedEffect(Unit) {
        return@LaunchedEffect
        delay(500)
        startQRCode(previewView, context, lifecycleOwner)
    }

    Column {
        Text(
            text = "Hello $name!",
            modifier = modifier
        )
        Button(onClick = {
            scope.launch {
                helper.startAndWait(JdcrCameraStartConfig.Test)
            }
        }) {
            Text("开启")
        }
        Button(onClick = {
            scope.launch {
                previewView.startCapture()
            }
        }) {
            Text("截图")
        }
        Button(onClick = {
            scope.launch {
                helper.closeAndWait()
            }
        }) {
            Text("关闭")
        }
        AndroidView(factory = { context ->
            previewView
        }, update = {

        })
    }
}

private suspend fun startQRCode(
    previewView: JdcrCustomPreviewView,
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner
) {
    val helper = JdcrCameraHelper(context, lifecycleOwner, previewView)
    helper.startAndWait(JdcrCameraStartConfig.QRCode)
    val scanner = JdcrQRCodeHelper()
    helper.getImageAnalysisBitmapFlow().collect {
        scanner.scan(it).onSuccess {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    JdcrCameraCommonTheme {
        Greeting("Android")
    }
}