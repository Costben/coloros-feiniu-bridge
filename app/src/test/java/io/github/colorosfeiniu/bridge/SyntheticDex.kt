package io.github.colorosfeiniu.bridge

import java.io.ByteArrayOutputStream

/**
 * Builds a minimal but structurally valid DEX image so the bridge's own DEX parsing can be covered
 * without shipping a real Gallery APK into the repository.
 *
 * Only the shapes the bridge looks at are emitted: a string pool, type/proto/method id tables,
 * class defs with virtual methods, and code items whose sole instructions are `const-string`
 * followed by `return-object`.
 */
internal object SyntheticDex {

    /** Proto `()Ljava/lang/String;`. */
    const val PROTO_NO_ARG = 0

    /** Proto `(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;`. */
    const val PROTO_TWO_STRINGS = 1

    data class Method(
        val name: String,
        val proto: Int,
        /** String this method loads with `const-string`, or null for an empty body. */
        val constString: String? = null,
    )

    data class Clazz(
        val descriptor: String,
        val methods: List<Method>,
    )

    private const val HEADER_SIZE = 0x70
    private const val NO_INDEX = -1
    private const val OBJECT_DESCRIPTOR = "Ljava/lang/Object;"
    private const val STRING_DESCRIPTOR = "Ljava/lang/String;"
    private const val SHORTY_NO_ARG = "L"
    private const val SHORTY_TWO_STRINGS = "LLL"

    fun build(classes: List<Clazz>): ByteArray {
        val strings = buildStringPool(classes)
        val stringIndex = strings.withIndex().associate { (i, s) -> s to i }

        val typeDescriptors = (classes.map { it.descriptor } + OBJECT_DESCRIPTOR + STRING_DESCRIPTOR)
            .distinct()
            .sortedBy { stringIndex.getValue(it) }
        val typeIndex = typeDescriptors.withIndex().associate { (i, d) -> d to i }

        // (classTypeIdx, protoIdx, nameStringIdx), ordered the way the DEX spec requires.
        val methodIds = classes.flatMap { clazz ->
            clazz.methods.map {
                Triple(typeIndex.getValue(clazz.descriptor), it.proto, stringIndex.getValue(it.name))
            }
        }.sortedWith(compareBy({ it.first }, { it.third }, { it.second }))
        val methodIdIndex = methodIds.withIndex().associate { (i, m) -> m to i }

        fun methodIdOf(clazz: Clazz, method: Method) = methodIdIndex.getValue(
            Triple(
                typeIndex.getValue(clazz.descriptor),
                method.proto,
                stringIndex.getValue(method.name),
            ),
        )

        val stringCount = strings.size
        val typeCount = typeDescriptors.size
        val protoCount = 2
        val methodCount = methodIds.size
        val classCount = classes.size

        val stringIdsOff = HEADER_SIZE
        val typeIdsOff = stringIdsOff + 4 * stringCount
        val protoIdsOff = typeIdsOff + 4 * typeCount
        val methodIdsOff = protoIdsOff + 12 * protoCount
        val classDefsOff = methodIdsOff + 8 * methodCount
        val dataOff = classDefsOff + 32 * classCount

        val data = Section(dataOff)

        val stringDataOffsets = strings.map { value ->
            data.offset().also {
                data.uleb128(value.length)
                data.bytes(value.toByteArray(Charsets.UTF_8))
                data.byte(0)
            }
        }

        data.align(4)
        val twoStringTypeListOff = data.offset()
        data.uint(2)
        data.ushort(typeIndex.getValue(STRING_DESCRIPTOR))
        data.ushort(typeIndex.getValue(STRING_DESCRIPTOR))

        val codeOffsets = mutableMapOf<Pair<String, String>, Int>()
        for (clazz in classes) {
            for (method in clazz.methods) {
                data.align(4)
                codeOffsets[clazz.descriptor to method.name] = data.offset()
                val insns = ByteArrayOutputStream()
                method.constString?.let { value ->
                    val idx = stringIndex.getValue(value)
                    insns.write(0x1a) // const-string v0, <string@BBBB>
                    insns.write(0x00)
                    insns.write(idx and 0xff)
                    insns.write((idx ushr 8) and 0xff)
                }
                insns.write(0x11) // return-object v0
                insns.write(0x00)
                val insnBytes = insns.toByteArray()
                val insArgs = if (method.proto == PROTO_TWO_STRINGS) 3 else 1
                data.ushort(insArgs + 1) // registers_size
                data.ushort(insArgs)     // ins_size
                data.ushort(0)           // outs_size
                data.ushort(0)           // tries_size
                data.uint(0)             // debug_info_off
                data.uint(insnBytes.size / 2)
                data.bytes(insnBytes)
            }
        }

        val classDataOffsets = classes.associate { clazz ->
            clazz.descriptor to data.offset().also {
                data.uleb128(0) // static_fields_size
                data.uleb128(0) // instance_fields_size
                data.uleb128(0) // direct_methods_size
                data.uleb128(clazz.methods.size)
                var previous = 0
                for (method in clazz.methods.sortedBy { methodIdOf(clazz, it) }) {
                    val id = methodIdOf(clazz, method)
                    data.uleb128(id - previous)
                    previous = id
                    data.uleb128(0x11) // public final
                    data.uleb128(codeOffsets.getValue(clazz.descriptor to method.name))
                }
            }
        }

        val dataBytes = data.toByteArray()
        val fileSize = dataOff + dataBytes.size
        val image = ByteArray(fileSize)
        val out = Cursor(image)

        out.at(0).bytes("dex\n035 ".toByteArray(Charsets.US_ASCII))
        out.at(0x20).uint(fileSize)
        out.at(0x24).uint(HEADER_SIZE)
        out.at(0x28).uint(0x12345678)
        out.at(0x38).uint(stringCount).uint(stringIdsOff)
        out.at(0x40).uint(typeCount).uint(typeIdsOff)
        out.at(0x48).uint(protoCount).uint(protoIdsOff)
        out.at(0x50).uint(0).uint(0) // field_ids
        out.at(0x58).uint(methodCount).uint(methodIdsOff)
        out.at(0x60).uint(classCount).uint(classDefsOff)
        out.at(0x68).uint(dataBytes.size).uint(dataOff)

        out.at(stringIdsOff)
        stringDataOffsets.forEach { out.uint(it) }

        out.at(typeIdsOff)
        typeDescriptors.forEach { out.uint(stringIndex.getValue(it)) }

        out.at(protoIdsOff)
        out.uint(stringIndex.getValue(SHORTY_NO_ARG))
            .uint(typeIndex.getValue(STRING_DESCRIPTOR))
            .uint(0)
        out.uint(stringIndex.getValue(SHORTY_TWO_STRINGS))
            .uint(typeIndex.getValue(STRING_DESCRIPTOR))
            .uint(twoStringTypeListOff)

        out.at(methodIdsOff)
        methodIds.forEach { (classTypeIdx, protoIdx, nameIdx) ->
            out.ushort(classTypeIdx).ushort(protoIdx).uint(nameIdx)
        }

        out.at(classDefsOff)
        classes.forEach { clazz ->
            out.uint(typeIndex.getValue(clazz.descriptor))
                .uint(0x1) // public
                .uint(typeIndex.getValue(OBJECT_DESCRIPTOR))
                .uint(0) // interfaces_off
                .uint(NO_INDEX) // source_file_idx
                .uint(0) // annotations_off
                .uint(classDataOffsets.getValue(clazz.descriptor))
                .uint(0) // static_values_off
        }

        dataBytes.copyInto(image, dataOff)
        return image
    }

    private fun buildStringPool(classes: List<Clazz>): List<String> {
        val values = mutableSetOf(
            SHORTY_NO_ARG,
            SHORTY_TWO_STRINGS,
            OBJECT_DESCRIPTOR,
            STRING_DESCRIPTOR,
        )
        classes.forEach { clazz ->
            values += clazz.descriptor
            clazz.methods.forEach { method ->
                values += method.name
                method.constString?.let { values += it }
            }
        }
        // The DEX spec orders string_ids by MUTF-8 byte value; the fixtures stay ASCII.
        return values.sorted()
    }

    private class Section(private val base: Int) {
        private val buffer = ByteArrayOutputStream()

        fun offset(): Int = base + buffer.size()

        fun align(boundary: Int) {
            while (offset() % boundary != 0) buffer.write(0)
        }

        fun byte(value: Int) = buffer.write(value and 0xff)

        fun bytes(value: ByteArray) = buffer.write(value, 0, value.size)

        fun ushort(value: Int) {
            byte(value)
            byte(value ushr 8)
        }

        fun uint(value: Int) {
            byte(value)
            byte(value ushr 8)
            byte(value ushr 16)
            byte(value ushr 24)
        }

        fun uleb128(value: Int) {
            var remaining = value
            do {
                var piece = remaining and 0x7f
                remaining = remaining ushr 7
                if (remaining != 0) piece = piece or 0x80
                byte(piece)
            } while (remaining != 0)
        }

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }

    private class Cursor(private val target: ByteArray) {
        private var position = 0

        fun at(offset: Int): Cursor {
            position = offset
            return this
        }

        fun bytes(value: ByteArray): Cursor {
            value.copyInto(target, position)
            position += value.size
            return this
        }

        fun ushort(value: Int): Cursor {
            target[position++] = (value and 0xff).toByte()
            target[position++] = ((value ushr 8) and 0xff).toByte()
            return this
        }

        fun uint(value: Int): Cursor {
            target[position++] = (value and 0xff).toByte()
            target[position++] = ((value ushr 8) and 0xff).toByte()
            target[position++] = ((value ushr 16) and 0xff).toByte()
            target[position++] = ((value ushr 24) and 0xff).toByte()
            return this
        }
    }
}
