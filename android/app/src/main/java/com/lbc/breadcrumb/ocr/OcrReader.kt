package com.lbc.breadcrumb.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the text out of one stored image. An interface so [OcrQueue]'s
 * bookkeeping can be tested without running a recognizer.
 */
fun interface OcrReader {

    /**
     * @return the text found, cleaned by [OcrRules.clean]. Empty when there is
     *   none, and also when the file does not decode as an image -- both are
     *   final answers that reading again would only repeat.
     * @throws Exception when the read failed in a way that might not recur.
     */
    suspend fun read(file: File): String

    /** Lets go of the recognizer between batches; the next [read] brings it back. */
    fun release() {}
}

/**
 * ML Kit Text Recognition v2, Latin script, with its model bundled in the APK
 * (architecture rule 4: free, instant, offline). Other scripts are separate
 * models of a few MB each -- worth adding once screenshots in one turn up.
 *
 * Not safe for concurrent use; [OcrQueue] reads one image at a time.
 */
class MlKitOcrReader : OcrReader {

    private var recognizer: TextRecognizer? = null

    override suspend fun read(file: File): String {
        val image = decode(file) ?: return ""
        return recognize(image)
    }

    /** Reads a bitmap already in hand -- a PDF page rendered for the purpose. */
    suspend fun read(bitmap: Bitmap): String = recognize(InputImage.fromBitmap(bitmap, 0))

    private suspend fun recognize(image: InputImage): String {
        val client = recognizer
            ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
        return OcrRules.clean(client.process(image).await().text)
    }

    override fun release() {
        recognizer?.close()
        recognizer = null
    }

    /**
     * Decodes the bitmap here rather than with `InputImage.fromFilePath`, which
     * decodes at full size: fine for a screenshot, an out-of-memory crash for a
     * 50 MP photo.
     */
    private fun decode(file: File): InputImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = OcrRules.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap: Bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null

        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        return InputImage.fromBitmap(bitmap, OcrRules.rotationDegrees(orientation))
    }
}

/** Runs the callback on whichever ML Kit thread completes the task, not the main thread. */
private val direct = Executor(Runnable::run)

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(direct) { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
