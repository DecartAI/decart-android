package ai.decart.sdk.queue

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * Represents a file input for queue job submission.
 *
 * Supports common Android file sources: [Uri] (gallery picker, camera),
 * [File] (local storage), [ByteArray] (in-memory), and [InputStream] (streaming).
 *
 * Usage:
 * ```kotlin
 * // From gallery picker result
 * val input = FileInput.fromUri(uri)
 *
 * // From a file on disk
 * val input = FileInput.fromFile(File("/path/to/video.mp4"))
 *
 * // From raw bytes
 * val input = FileInput.fromBytes(videoBytes, "video/mp4")
 * ```
 */
sealed class FileInput {

    /**
     * File input from an Android content [Uri].
     * Resolved via [ContentResolver] at submission time.
     * This is the most common source on Android (gallery picker, camera capture, etc.).
     */
    data class FromUri(val uri: Uri) : FileInput()

    /**
     * File input from a [java.io.File] on local storage.
     */
    data class FromFile(val file: File) : FileInput()

    /**
     * File input from raw bytes already in memory.
     *
     * @param bytes The file content
     * @param mimeType MIME type (e.g., "video/mp4", "image/png")
     */
    data class FromBytes(val bytes: ByteArray, val mimeType: String = "application/octet-stream") : FileInput() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FromBytes) return false
            return bytes.contentEquals(other.bytes) && mimeType == other.mimeType
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            return result
        }
    }

    /**
     * File input from an [InputStream].
     * The stream is read fully at submission time and then closed.
     *
     * @param stream The input stream to read from
     * @param mimeType MIME type (e.g., "video/mp4", "image/png")
     */
    data class FromInputStream(val stream: InputStream, val mimeType: String = "application/octet-stream") : FileInput()

    companion object {
        /** Create a [FileInput] from an Android content [Uri]. */
        fun fromUri(uri: Uri): FileInput = FromUri(uri)

        /** Create a [FileInput] from a local [File]. */
        fun fromFile(file: File): FileInput = FromFile(file)

        /** Create a [FileInput] from raw [ByteArray]. */
        fun fromBytes(bytes: ByteArray, mimeType: String = "application/octet-stream"): FileInput =
            FromBytes(bytes, mimeType)

        /** Create a [FileInput] from an [InputStream]. */
        fun fromInputStream(stream: InputStream, mimeType: String = "application/octet-stream"): FileInput =
            FromInputStream(stream, mimeType)
    }
}
