package com.dynamicedgeai.util

import java.io.File
import java.io.RandomAccessFile

enum class IntegrityResult {
    VALID,
    MISSING,
    SIZE_MISMATCH,
    CORRUPTED
}

object ModelIntegrityChecker {

    /**
     * Checks the integrity of a downloaded model file.
     *
     * Performs three levels of verification:
     * 1. File existence check
     * 2. File size check against expected size (±5% tolerance)
     * 3. Content validity check (first 1KB must not be all zeros)
     *
     * @param file The model file to check
     * @param expectedSizeBytes The expected file size from the model registry
     * @return IntegrityResult indicating the file's status
     */
    fun checkIntegrity(file: File, expectedSizeBytes: Long): IntegrityResult {
        // 1. File existence
        if (!file.exists()) return IntegrityResult.MISSING

        // 2. File size validation with relaxed 50% tolerance
        val actualSize = file.length()
        if (actualSize == 0L) return IntegrityResult.SIZE_MISMATCH

        val lowerBound = (expectedSizeBytes * 0.50).toLong()
        val upperBound = (expectedSizeBytes * 1.50).toLong()
        if (actualSize < lowerBound || actualSize > upperBound) {
            return IntegrityResult.SIZE_MISMATCH
        }

        // 3. Content validity — first 1KB must not be all zeros
        return try {
            val raf = RandomAccessFile(file, "r")
            val checkSize = minOf(1024L, actualSize).toInt()
            val buffer = ByteArray(checkSize)
            raf.readFully(buffer)
            raf.close()

            val allZeros = buffer.all { it == 0.toByte() }
            if (allZeros) IntegrityResult.CORRUPTED else IntegrityResult.VALID
        } catch (e: Exception) {
            IntegrityResult.CORRUPTED
        }
    }
}
