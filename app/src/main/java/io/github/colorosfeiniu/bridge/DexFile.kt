package io.github.colorosfeiniu.bridge

/**
 * Minimal read-only DEX reader.
 *
 * Only the tables the bridge actually needs are decoded, and nothing here touches Android APIs, so
 * the parsing stays covered by plain JVM unit tests. Every accessor is bounds checked because the
 * input is whatever APK happens to be installed on the device.
 */
internal class DexFile private constructor(private val data: ByteArray) {

    private val stringIdsSize = data.uintAt(STRING_IDS_SIZE)
    private val stringIdsOffset = data.uintAt(STRING_IDS_OFFSET)
    private val typeIdsSize = data.uintAt(TYPE_IDS_SIZE)
    private val typeIdsOffset = data.uintAt(TYPE_IDS_OFFSET)
    private val protoIdsSize = data.uintAt(PROTO_IDS_SIZE)
    private val protoIdsOffset = data.uintAt(PROTO_IDS_OFFSET)
    private val methodIdsSize = data.uintAt(METHOD_IDS_SIZE)
    private val methodIdsOffset = data.uintAt(METHOD_IDS_OFFSET)
    private val classDefsSize = data.uintAt(CLASS_DEFS_SIZE)
    private val classDefsOffset = data.uintAt(CLASS_DEFS_OFFSET)

    private val usable = stringIdsSize > 0 && stringIdsOffset > 0

    /** The first string in the pool matching [predicate], or null when there is none. */
    fun firstString(predicate: (String) -> Boolean): String? {
        if (!usable) return null
        for (index in 0 until stringIdsSize) {
            val value = string(index) ?: continue
            if (predicate(value)) return value
        }
        return null
    }

    /** Pool index of [value], or -1 when the string is absent. */
    fun indexOfString(value: String): Int {
        if (!usable) return -1
        for (index in 0 until stringIdsSize) {
            if (string(index) == value) return index
        }
        return -1
    }

    /** The first defined class matching [predicate], or null when there is none. */
    fun firstClass(predicate: (DexClass) -> Boolean): DexClass? {
        if (classDefsSize <= 0 || classDefsOffset <= 0) return null
        for (index in 0 until classDefsSize) {
            val entry = classDefsOffset + index * CLASS_DEF_SIZE
            if (entry < 0 || entry + CLASS_DEF_SIZE > data.size) return null
            val descriptor = typeDescriptor(data.uintAt(entry)) ?: continue
            val classDataOffset = data.uintAt(entry + CLASS_DEF_DATA_OFFSET)
            if (classDataOffset <= 0) continue
            val clazz = DexClass(descriptor, classDataOffset)
            if (predicate(clazz)) return clazz
        }
        return null
    }

    private fun string(index: Int): String? {
        if (index < 0 || index >= stringIdsSize) return null
        val idOffset = stringIdsOffset + index * STRING_ID_SIZE
        if (idOffset < 0 || idOffset + STRING_ID_SIZE > data.size) return null
        return stringAt(data.uintAt(idOffset))
    }

    private fun stringAt(offset: Int): String? {
        if (offset < 0 || offset >= data.size) return null
        var cursor = offset
        while (cursor < data.size) {
            val byte = data[cursor].toInt() and 0xff
            cursor++
            if (byte and 0x80 == 0) break
        }
        if (cursor >= data.size) return null

        val start = cursor
        while (cursor < data.size && data[cursor].toInt() != 0) cursor++
        if (cursor > data.size || cursor == start) return null

        return runCatching { String(data, start, cursor - start, Charsets.UTF_8) }.getOrNull()
    }

    private fun typeDescriptor(index: Int): String? {
        if (index < 0 || index >= typeIdsSize) return null
        val entry = typeIdsOffset + index * TYPE_ID_SIZE
        if (entry < 0 || entry + TYPE_ID_SIZE > data.size) return null
        return string(data.uintAt(entry))
    }

    private fun protoDescriptor(index: Int): String? {
        if (index < 0 || index >= protoIdsSize) return null
        val entry = protoIdsOffset + index * PROTO_ID_SIZE
        if (entry < 0 || entry + PROTO_ID_SIZE > data.size) return null

        val returnType = typeDescriptor(data.uintAt(entry + PROTO_ID_RETURN_TYPE)) ?: return null
        val parametersOffset = data.uintAt(entry + PROTO_ID_PARAMETERS)
        if (parametersOffset <= 0) return "()$returnType"
        if (parametersOffset + 4 > data.size) return null

        val parameters = StringBuilder()
        val count = data.uintAt(parametersOffset)
        for (position in 0 until count) {
            val at = parametersOffset + 4 + position * 2
            if (at + 2 > data.size) return null
            parameters.append(typeDescriptor(data.ushortAt(at)) ?: return null)
        }
        return "($parameters)$returnType"
    }

    /** Resolves a `method_id` into its name and `(params)return` descriptor. */
    private fun methodRef(index: Int): Pair<String, String>? {
        if (index < 0 || index >= methodIdsSize) return null
        val entry = methodIdsOffset + index * METHOD_ID_SIZE
        if (entry < 0 || entry + METHOD_ID_SIZE > data.size) return null
        val proto = protoDescriptor(data.ushortAt(entry + METHOD_ID_PROTO)) ?: return null
        val name = string(data.uintAt(entry + METHOD_ID_NAME)) ?: return null
        return name to proto
    }

    inner class DexClass internal constructor(
        val descriptor: String,
        private val classDataOffset: Int,
    ) {
        /** `Lcom/oplus/aiunit/vision/qp80;` becomes `com.oplus.aiunit.vision.qp80`. */
        val className: String
            get() = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')

        private val methods: List<DexMethod> by lazy { readMethods() }

        fun declaresMethod(name: String, descriptor: String): Boolean =
            methods.any { it.name == name && it.descriptor == descriptor }

        fun referencesString(stringIndex: Int): Boolean =
            stringIndex >= 0 && methods.any { it.referencesString(stringIndex) }

        private fun readMethods(): List<DexMethod> {
            val reader = Reader(classDataOffset)
            val staticFields = reader.uleb128()
            val instanceFields = reader.uleb128()
            val directMethods = reader.uleb128()
            val virtualMethods = reader.uleb128()
            if (staticFields < 0 || instanceFields < 0 || directMethods < 0 || virtualMethods < 0) {
                return emptyList()
            }

            repeat(staticFields + instanceFields) {
                reader.uleb128()
                reader.uleb128()
            }

            val result = mutableListOf<DexMethod>()
            for (count in listOf(directMethods, virtualMethods)) {
                var methodIndex = 0
                repeat(count) {
                    val diff = reader.uleb128()
                    reader.uleb128() // access_flags
                    val codeOffset = reader.uleb128()
                    if (diff < 0 || codeOffset < 0) return result
                    methodIndex += diff
                    methodRef(methodIndex)?.let { (name, descriptor) ->
                        result += DexMethod(name, descriptor, codeOffset)
                    }
                }
            }
            return result
        }
    }

    private inner class DexMethod(
        val name: String,
        val descriptor: String,
        private val codeOffset: Int,
    ) {
        fun referencesString(stringIndex: Int): Boolean {
            if (codeOffset <= 0 || codeOffset + CODE_ITEM_HEADER_SIZE > data.size) return false
            val units = data.uintAt(codeOffset + CODE_ITEM_INSNS_SIZE)
            if (units <= 0) return false
            val start = codeOffset + CODE_ITEM_HEADER_SIZE
            val end = start + units * 2
            if (end > data.size) return false

            var cursor = start
            while (cursor + 2 <= end) {
                val opcode = data[cursor].toInt() and 0xff
                when {
                    opcode == OP_CONST_STRING && cursor + 4 <= end ->
                        if (data.ushortAt(cursor + 2) == stringIndex) return true

                    opcode == OP_CONST_STRING_JUMBO && cursor + 6 <= end ->
                        if (data.uintAt(cursor + 2) == stringIndex) return true
                }
                cursor += instructionUnits(opcode) * 2
            }
            return false
        }
    }

    private inner class Reader(private var offset: Int) {
        fun uleb128(): Int {
            var result = 0
            var shift = 0
            while (offset < data.size) {
                val byte = data[offset].toInt() and 0xff
                offset++
                result = result or ((byte and 0x7f) shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift > 28) return -1
            }
            return -1
        }
    }

    companion object {
        private const val HEADER_SIZE = 0x70
        private const val STRING_IDS_SIZE = 0x38
        private const val STRING_IDS_OFFSET = 0x3c
        private const val TYPE_IDS_SIZE = 0x40
        private const val TYPE_IDS_OFFSET = 0x44
        private const val PROTO_IDS_SIZE = 0x48
        private const val PROTO_IDS_OFFSET = 0x4c
        private const val METHOD_IDS_SIZE = 0x58
        private const val METHOD_IDS_OFFSET = 0x5c
        private const val CLASS_DEFS_SIZE = 0x60
        private const val CLASS_DEFS_OFFSET = 0x64

        private const val STRING_ID_SIZE = 4
        private const val TYPE_ID_SIZE = 4
        private const val PROTO_ID_SIZE = 12
        private const val PROTO_ID_RETURN_TYPE = 4
        private const val PROTO_ID_PARAMETERS = 8
        private const val METHOD_ID_SIZE = 8
        private const val METHOD_ID_PROTO = 2
        private const val METHOD_ID_NAME = 4
        private const val CLASS_DEF_SIZE = 32
        private const val CLASS_DEF_DATA_OFFSET = 24
        private const val CODE_ITEM_HEADER_SIZE = 16
        private const val CODE_ITEM_INSNS_SIZE = 12

        private const val OP_CONST_STRING = 0x1a
        private const val OP_CONST_STRING_JUMBO = 0x1b

        /**
         * Instruction width in 16-bit code units, indexed by opcode. Walking instructions properly
         * keeps `const-string` operands from being read out of a misaligned position.
         */
        private val INSTRUCTION_UNITS = IntArray(256) { 1 }.apply {
            fun span(range: IntRange, units: Int) = range.forEach { this[it] = units }
            span(0x02..0x02, 2); span(0x03..0x03, 3)
            span(0x05..0x05, 2); span(0x06..0x06, 3)
            span(0x08..0x08, 2); span(0x09..0x09, 3)
            span(0x13..0x13, 2); span(0x14..0x14, 3)
            span(0x15..0x16, 2); span(0x17..0x17, 3); span(0x18..0x18, 5)
            span(0x19..0x1a, 2); span(0x1b..0x1b, 3); span(0x1c..0x1c, 2)
            span(0x1f..0x20, 2); span(0x22..0x23, 2); span(0x24..0x26, 3)
            span(0x29..0x29, 2); span(0x2a..0x2c, 3)
            span(0x2d..0x3d, 2)
            span(0x44..0x6d, 2)
            span(0x6e..0x72, 3); span(0x74..0x78, 3)
            span(0x90..0xaf, 2)
            span(0xd0..0xe2, 2)
            span(0xfa..0xfb, 4); span(0xfc..0xfd, 3); span(0xfe..0xff, 2)
        }

        private fun instructionUnits(opcode: Int): Int =
            INSTRUCTION_UNITS.getOrElse(opcode) { 1 }.coerceAtLeast(1)

        /** Returns a reader for [bytes], or null when they are not a standard DEX image. */
        fun parse(bytes: ByteArray): DexFile? {
            if (bytes.size < HEADER_SIZE) return null
            if (bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() ||
                bytes[2] != 'x'.code.toByte() || bytes[3] != '\n'.code.toByte()
            ) {
                return null
            }
            return DexFile(bytes)
        }

        private fun ByteArray.uintAt(offset: Int): Int {
            if (offset < 0 || offset + 4 > size) return -1
            return (this[offset].toInt() and 0xff) or
                ((this[offset + 1].toInt() and 0xff) shl 8) or
                ((this[offset + 2].toInt() and 0xff) shl 16) or
                ((this[offset + 3].toInt() and 0xff) shl 24)
        }

        private fun ByteArray.ushortAt(offset: Int): Int {
            if (offset < 0 || offset + 2 > size) return -1
            return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
        }
    }
}
