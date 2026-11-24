package com.sztu.schedule.duifene

import android.graphics.BitmapFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader

/** ZXing Java core 3.5.3。只解码，不打开二维码里的地址。 */
class AndroidQrDecoder : QrImageDecoder {
    override fun read(bytes: ByteArray): List<String> {
        if (bytes.size > DuifenePolicy.MAX_QR_TEXT_CHARS) throw AppApiError("网页二维码图片数据异常")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        checkImageBounds(bounds.outWidth, bounds.outHeight)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw AppApiError("网页二维码图片无法解析")
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true,
                    )
                )
            }
            val found = try {
                GenericMultipleBarcodeReader(reader).decodeMultiple(binary).map { it.text }
            } catch (_: Exception) {
                listOf(reader.decode(binary).text)
            }
            found
        } catch (error: AppApiError) {
            throw error
        } catch (_: Exception) {
            throw AppApiError("网页二维码图片无法解析")
        } finally {
            bitmap.recycle()
        }
    }
}
