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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jdcr.jdcrcamerabase.JdcrCameraHelper
import com.jdcr.jdcrcamerabase.JdcrCustomPreviewView
import com.jdcr.jdcrcamerabase.config.JdcrCameraPreviewConfig
import com.jdcr.jdcrcamerabase.config.JdcrCameraStartConfig
import com.jdcr.jdcrcameracommon.ui.theme.JdcrCameraCommonTheme
import com.jdcr.jdcrcameragesture.JdcrGestureRecognizerHelper
import com.jdcr.jdcrqrcode.JdcrQRCodeHelper
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

    val previewView = remember { JdcrCustomPreviewView(context, JdcrCameraPreviewConfig.getTest()) }

    LaunchedEffect(Unit) {
        return@LaunchedEffect
        delay(500)
        val modelAssetPath = "mediapipe/model/gesture_recognizer.task"
        val recognizer = JdcrGestureRecognizerHelper(context, modelAssetPath)
        startCamera(previewView, context, lifecycleOwner).apply {
            getImageAnalysisBitmapFlow().collect {
                recognizer.recognizeBitmap(it)
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(500)
        startQRCode(previewView, context, lifecycleOwner)
    }

    Column {
        Text(
            text = "Hello $name!",
            modifier = modifier
        )
        AndroidView(factory = { context ->
            previewView
        }, update = {

        })
    }
}

private suspend fun startCamera(
    previewView: JdcrCustomPreviewView,
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner
): JdcrCameraHelper {
    val helper = JdcrCameraHelper(context, lifecycleOwner, previewView)
    helper.startAndWait(JdcrCameraStartConfig.Test)
    return helper
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