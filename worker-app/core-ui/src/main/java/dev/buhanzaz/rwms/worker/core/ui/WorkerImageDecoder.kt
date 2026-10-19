package dev.buhanzaz.rwms.worker.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Decodes authenticated worker media with a pixel budget so a valid high-resolution JPEG cannot
 * exhaust the process while it is shown as a thumbnail or full-screen page.
 */
fun decodeWorkerBitmap(bytes: ByteArray, maxPixels: Long): Bitmap {
    require(bytes.isNotEmpty()) { "RWMS вернул пустое изображение" }
    require(maxPixels > 0) { "Pixel budget must be positive" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "RWMS вернул не изображение" }
    val options = BitmapFactory.Options().apply {
        inSampleSize = workerBitmapSampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) {
        "Не удалось декодировать изображение"
    }
}

/** Decodes a private worker image file with the same pixel budget as authenticated media. */
fun decodeWorkerBitmapFile(file: File, maxPixels: Long): Bitmap {
    require(file.isFile && file.length() > 0L) { "RWMS сохранил пустое изображение" }
    require(maxPixels > 0) { "Pixel budget must be positive" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "RWMS сохранил не изображение" }
    val options = BitmapFactory.Options().apply {
        inSampleSize = workerBitmapSampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return requireNotNull(BitmapFactory.decodeFile(file.path, options)) {
        "Не удалось декодировать изображение"
    }
}

/** Returns the smallest power-of-two sample that stays inside [maxPixels]. */
fun workerBitmapSampleSize(width: Int, height: Int, maxPixels: Long): Int {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    require(maxPixels > 0) { "Pixel budget must be positive" }
    var sample = 1
    while (sampledPixelCount(width, height, sample) > maxPixels) sample *= 2
    return sample
}

/** Reads an authenticated media response without trusting a missing Content-Length header. */
fun InputStream.readWorkerImageBytes(maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "Byte budget must be positive" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        total += count
        require(total <= maxBytes) { "Файл слишком большой для просмотра" }
        output.write(buffer, 0, count)
    }
}

private fun sampledPixelCount(width: Int, height: Int, sample: Int): Long {
    val sampledWidth = (width.toLong() + sample - 1L) / sample
    val sampledHeight = (height.toLong() + sample - 1L) / sample
    return sampledWidth * sampledHeight
}
