package dev.continuo.build

import java.io.File

object ClassScan {

    /** Major class-file version. 52 = Java 8, 65 = Java 21. */
    fun majorVersion(classFile: File): Int {
        val bytes = classFile.readBytes()
        require(bytes.size >= 8) { "Not a class file: $classFile" }
        return ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
    }

    /** True if the raw bytes of the class file contain [needle] as ASCII. */
    fun containsAscii(classFile: File, needle: String): Boolean {
        val haystack = classFile.readBytes()
        val pattern = needle.toByteArray(Charsets.US_ASCII)
        if (pattern.isEmpty() || haystack.size < pattern.size) return false
        outer@ for (start in 0..haystack.size - pattern.size) {
            for (i in pattern.indices) {
                if (haystack[start + i] != pattern[i]) continue@outer
            }
            return true
        }
        return false
    }

    /** All `.class` files under [dir], or empty if it does not exist. */
    fun classFiles(dir: File): List<File> =
        if (!dir.exists()) emptyList()
        else dir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
}
