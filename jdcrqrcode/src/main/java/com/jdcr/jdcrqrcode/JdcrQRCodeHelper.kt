package com.jdcr.jdcrqrcode

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JdcrQRCodeHelper {

    suspend fun scan(bitmap: Bitmap): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                val source = RGBLuminanceSource(width, height, pixels)
                val binarizer = HybridBinarizer(source)
                val binaryBitmap = BinaryBitmap(binarizer)

                val reader = MultiFormatReader()
                val result = reader.decode(binaryBitmap).text
                JdcrQRCodeLog.i("二维码识别结果:$result")
                Result.success(result)
            } catch (e: NotFoundException) {
                JdcrQRCodeLog.e("未发现二维码:$e")
                Result.failure(e)
            } catch (e: Exception) {
                JdcrQRCodeLog.e("扫描发生错误:$e")
                Result.failure(e)
            }
        }
    }

}