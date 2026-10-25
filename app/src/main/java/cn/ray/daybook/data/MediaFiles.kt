package cn.ray.daybook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import cn.ray.daybook.model.Rules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

class MediaFiles(private val context: Context) {
    val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
    val stagingDir = File(context.filesDir, "staging").apply { mkdirs() }
    private val thumbs = File(context.cacheDir, "thumbs").apply { mkdirs() }
    fun original(asset: MediaAsset) = File(context.filesDir, asset.relativePath)
    fun thumbnail(asset: MediaAsset): File = File(thumbs, "${asset.id}.jpg").takeIf { it.exists() } ?: original(asset)

    suspend fun import(uri: Uri): MediaAsset = withContext(Dispatchers.IO) {
        val temporary = File(stagingDir, "image-${UUID.randomUUID()}.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output -> copyLimited(input, output, Rules.MAX_IMAGE_BYTES); output.fd.sync() }
            } ?: error("无法读取这张图片，请选择本机文件")
            val info = inspect(temporary)
            val hash = digest(temporary)
            val ext = when(info.mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; else -> "webp" }
            val relative = "media/$hash.$ext"
            val destination = File(context.filesDir, relative)
            if (!destination.exists()) check(temporary.renameTo(destination)) { "无法保存图片，检查剩余空间" }
            val asset = MediaAsset(hash, relative, hash, info.mime, destination.length(), info.width, info.height, System.currentTimeMillis())
            makeThumbnail(asset) // Decode once before accepting a durable image reference.
            asset
        } finally { temporary.delete() }
    }

    data class ImageInfo(val mime: String, val width: Int, val height: Int)
    fun inspect(file: File): ImageInfo {
        require(file.length() in 1..Rules.MAX_IMAGE_BYTES) { "单张图片需小于 25 MiB" }
        val header = ByteArray(16)
        file.inputStream().use { it.read(header) }
        val mime = when {
            header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> "image/jpeg"
            header.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)) -> "image/png"
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> error("仅支持 JPEG、PNG 和静态 WebP 图片")
        }
        // Reject animated variants rather than silently flattening them.
        if (mime != "image/jpeg") requireStaticChunks(file, mime)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 50_000_000) { "图片无效或超过 5000 万像素" }
        return ImageInfo(mime, options.outWidth, options.outHeight)
    }

    fun makeThumbnail(asset: MediaAsset) {
        val target = File(thumbs, "${asset.id}.jpg")
        if (target.exists()) return
        var sample = 1
        while (maxOf(asset.width, asset.height) / sample > 1024) sample *= 2
        val decoded = BitmapFactory.decodeFile(original(asset).path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("图片内容无法解码，请选择其他图片")
        var bitmap = decoded
        try {
            val orientation = ExifInterface(original(asset)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> { setRotate(180f); postScale(-1f, 1f) }
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(-90f); postScale(-1f, 1f) }
                    8 -> setRotate(-90f)
                }
            }
            if (!matrix.isIdentity) bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val scale = 512f / maxOf(bitmap.width, bitmap.height)
            val small = if (scale < 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
            try {
                val temp = File(thumbs, "${asset.id}.part")
                temp.outputStream().use { check(small.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                check(temp.renameTo(target))
            } finally { if (small !== bitmap) small.recycle() }
        } finally { if (bitmap !== decoded) bitmap.recycle(); decoded.recycle() }
    }

    companion object {
        fun digest(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        fun copyLimited(input: InputStream, output: java.io.OutputStream, limit: Long): Long {
            var total = 0L; val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= limit) { "文件超出大小限制" }; output.write(buffer, 0, n) }
            return total
        }
        private fun requireStaticChunks(file: File, mime: String) {
            java.io.RandomAccessFile(file, "r").use { input ->
                var position = if (mime == "image/png") 8L else 12L
                var chunks = 0
                while (position + 8 <= input.length()) {
                    require(++chunks <= 100_000) { "图片结构异常" }
                    input.seek(position)
                    val size: Long
                    val typeBytes = ByteArray(4)
                    if (mime == "image/png") { size = input.readInt().toLong() and 0xffffffffL; input.readFully(typeBytes) }
                    else { input.readFully(typeBytes); size = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL }
                    val type = String(typeBytes, Charsets.US_ASCII)
                    require(type !in listOf("acTL", "ANIM", "ANMF")) { "暂不支持动画图片" }
                    val next = position + 8 + size + if (mime == "image/png") 4 else size % 2
                    require(next <= input.length() && next > position) { "图片文件已损坏" }
                    position = next
                    if (mime == "image/png" && type == "IEND") break
                }
            }
        }
    }
}
