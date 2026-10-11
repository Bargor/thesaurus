package pl.bargor.thesaurus.ui.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test
import pl.bargor.thesaurus.data.transfer.BackupContractException
import pl.bargor.thesaurus.data.transfer.TransferErrorCode
import pl.bargor.thesaurus.data.transfer.DocumentStreams

class DocumentStreamsTest {
    @Test fun boundedReadAllowsExactLimitButRejectsOneMoreByte() {
        val bytes = byteArrayOf(0, 1, 2, 3)
        assertArrayEquals(bytes, DocumentStreams.readBounded(ByteArrayInputStream(bytes), 4))
        val failure = runCatching { DocumentStreams.readBounded(ByteArrayInputStream(bytes), 3) }.exceptionOrNull()
        assertTrue(failure is BackupContractException)
        assertEquals(TransferErrorCode.LIMIT_EXCEEDED, (failure as BackupContractException).code)
    }

    @Test fun shortReadsPreserveEveryByteAndStreamReadFailuresPropagate() {
        val bytes = "Synthetic Żółć\nrow".toByteArray()
        val source = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(length, 1))
        }
        assertArrayEquals(bytes, DocumentStreams.readBounded(source, bytes.size))
        val denied = IOException("provider unavailable")
        val broken = object : InputStream() { override fun read(): Int = throw denied }
        assertSame(denied, runCatching { DocumentStreams.readBounded(broken, 10) }.exceptionOrNull())
    }

    @Test fun completeWriteIncludesAllBytesAndFlushesBeforeReturning() {
        var flushed = false
        val destination = object : ByteArrayOutputStream() { override fun flush() { flushed = true } }
        val bytes = ByteArray(20000) { (it % 251).toByte() }
        DocumentStreams.writeComplete(destination, bytes)
        assertArrayEquals(bytes, destination.toByteArray())
        assertTrue(flushed)
    }

    @Test fun noSpaceAndFinalFlushFailureAreNotReportedAsSuccessfulWrites() {
        for (failOnFlush in listOf(false, true)) {
            val failure = IOException("No space")
            val output = object : OutputStream() {
                override fun write(value: Int) { if (!failOnFlush) throw failure }
                override fun flush() { if (failOnFlush) throw failure }
            }
            assertSame(failure, runCatching { DocumentStreams.writeComplete(output, byteArrayOf(1, 2)) }.exceptionOrNull())
        }
    }
}
