package pl.bargor.thesaurus.data.transfer

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Streams belong to the gateway; errors including close/flush failures are never success. */
class DocumentGateway @Inject constructor(@param:ApplicationContext private val context: Context) {
    suspend fun read(uri: String): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { DocumentStreams.readBounded(it) }
            ?: throw IOException("Document provider returned no stream")
    }

    suspend fun write(uri: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(Uri.parse(uri), "wt")?.use { DocumentStreams.writeComplete(it, bytes) }
            ?: throw IOException("Document provider returned no stream")
    }
}

object DocumentStreams {
    fun readBounded(input: InputStream, maxBytes: Int = BackupLimits.MAX_BYTES): ByteArray {
        require(maxBytes >= 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val remaining = maxBytes - output.size()
            val count = input.read(buffer, 0, if (remaining >= buffer.size) buffer.size else remaining + 1)
            if (count == -1) break
            if (count == 0) {
                val value = input.read()
                if (value == -1) break
                limit(output.size() < maxBytes)
                output.write(value)
            } else {
                limit(count <= maxBytes - output.size())
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    fun writeComplete(output: OutputStream, bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }
}
