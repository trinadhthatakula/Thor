package com.valhalla.bypass

import android.os.SharedMemory
import java.io.IOException
import java.lang.invoke.MethodHandle
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Executable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.PriorityQueue
import java.util.zip.ZipException

internal class DexFieldLayout {

    private val classes = HashMap<String, DexClass>()
    private val layouts = HashMap<String, Layout>()

    @Throws(IOException::class)
    fun scanPath(path: String) {
        if (path.isEmpty()) return
        val file = Paths.get(path)
        if (!Files.isRegularFile(file)) return

        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            try {
                val zipReader = ZipReader(mapped)
                var i = 1
                while (!hasAllClasses()) {
                    val entryName = if (i == 1) "classes.dex" else "classes$i.dex"
                    val dex = zipReader.getEntry(entryName) ?: break
                    DexReader(dex).scan(classes)
                    i++
                }
            } finally {
                SharedMemory.unmap(mapped)
            }
        }
    }

    private fun hasAllClasses(): Boolean {
        return classes.containsKey(CLASS) &&
                classes.containsKey(ACCESSIBLE_OBJECT) &&
                classes.containsKey(EXECUTABLE) &&
                classes.containsKey(METHOD_HANDLE)
    }

    @Throws(ClassNotFoundException::class)
    fun layoutOf(descriptor: String): Layout {
        if (OBJECT == descriptor) {
            return Layout(OBJECT_HEADER_SIZE, HashMap())
        }
        val cached = layouts[descriptor]
        if (cached != null) return cached

        val dexClass = classes[descriptor] ?: throw ClassNotFoundException(descriptor)
        val superLayout = layoutOf(dexClass.superDescriptor)

        val fields = ArrayList(dexClass.instanceFields)
        fields.sortWith(FIELD_COMPARATOR)

        val state = LinkState(superLayout.objectSize)
        val gaps = PriorityQueue(FIELD_GAP_COMPARATOR)
        val offsets = HashMap<String, Int>()

        while (state.index < fields.size) {
            val field = fields[state.index]
            if (field.isPrimitive) break
            if (!isAligned(state.offset, REFERENCE_SIZE)) {
                val oldOffset = state.offset
                state.offset = roundUp(state.offset, REFERENCE_SIZE)
                addFieldGap(oldOffset, state.offset, gaps)
            }
            offsets[field.name] = state.offset
            state.offset += REFERENCE_SIZE
            state.index++
        }

        shuffleForward(8, state, fields, gaps, offsets)
        shuffleForward(4, state, fields, gaps, offsets)
        shuffleForward(2, state, fields, gaps, offsets)
        shuffleForward(1, state, fields, gaps, offsets)

        val layout = Layout(state.offset, offsets)
        layouts[descriptor] = layout
        return layout
    }

    private fun shuffleForward(
        size: Int,
        state: LinkState,
        fields: ArrayList<DexField>,
        gaps: PriorityQueue<FieldGap>,
        offsets: HashMap<String, Int>
    ) {
        while (state.index < fields.size) {
            val field = fields[state.index]
            if (field.componentSize() < size) break
            if (!isAligned(state.offset, size)) {
                val oldOffset = state.offset
                state.offset = roundUp(state.offset, size)
                addFieldGap(oldOffset, state.offset, gaps)
            }

            val gap = gaps.peek()
            if (gap != null && gap.size >= size) {
                gaps.poll()
                offsets[field.name] = gap.startOffset
                if (gap.size > size) {
                    addFieldGap(gap.startOffset + size, gap.startOffset + gap.size, gaps)
                }
            } else {
                offsets[field.name] = state.offset
                state.offset += size
            }
            state.index++
        }
    }

    private fun addFieldGap(gapStart: Int, gapEnd: Int, gaps: PriorityQueue<FieldGap>) {
        var offset = gapStart
        while (offset != gapEnd) {
            val remaining = gapEnd - offset
            if (remaining >= 4 && isAligned(offset, 4)) {
                gaps.add(FieldGap(offset, 4))
                offset += 4
            } else if (remaining >= 2 && isAligned(offset, 2)) {
                gaps.add(FieldGap(offset, 2))
                offset += 2
            } else {
                gaps.add(FieldGap(offset, 1))
                offset += 1
            }
        }
    }

    private class ZipReader(private val fileData: ByteBuffer) {
        private val centralDirectoryOffset: Int
        private val entryCount: Int

        init {
            if (fileData.limit() < 22) {
                throw ZipException()
            }
            val endOfCentralDirectoryOffset = findEndOfCentralDirectory()
            entryCount = readUnsignedShort(endOfCentralDirectoryOffset + 10)
            centralDirectoryOffset = readInt(endOfCentralDirectoryOffset + 16)
        }

        @Throws(IOException::class)
        fun getEntry(name: String): ByteBuffer? {
            var offset = centralDirectoryOffset
            for (i in 0 until entryCount) {
                if (readInt(offset) != CENTRAL_DIRECTORY_SIGNATURE) {
                    throw ZipException()
                }

                val compressionMethod = readUnsignedShort(offset + 10)
                val compressedSize = readInt(offset + 20)
                val uncompressedSize = readInt(offset + 24)
                val fileNameLength = readUnsignedShort(offset + 28)
                val extraLength = readUnsignedShort(offset + 30)
                val commentLength = readUnsignedShort(offset + 32)
                val localHeaderOffset = readInt(offset + 42)
                val fileNameOffset = offset + 46
                if (matchesName(fileNameOffset, fileNameLength, name)) {
                    return openEntry(localHeaderOffset, compressionMethod, compressedSize, uncompressedSize)
                }
                offset = fileNameOffset + fileNameLength + extraLength + commentLength
            }
            return null
        }

        @Throws(IOException::class)
        private fun openEntry(
            localHeaderOffset: Int,
            compressionMethod: Int,
            compressedSize: Int,
            uncompressedSize: Int
        ): ByteBuffer {
            if (readInt(localHeaderOffset) != LOCAL_FILE_HEADER_SIGNATURE) {
                throw ZipException()
            }
            val fileNameLength = readUnsignedShort(localHeaderOffset + 26)
            val extraLength = readUnsignedShort(localHeaderOffset + 28)
            val dataOffset = localHeaderOffset + 30 + fileNameLength + extraLength
            if (compressionMethod != STORED || compressedSize != uncompressedSize) {
                throw ZipException()
            }
            return slice(fileData, dataOffset, uncompressedSize)
        }

        @Throws(ZipException::class)
        private fun findEndOfCentralDirectory(): Int {
            val start = 0.coerceAtLeast(fileData.limit() - MAX_EOCD_SEARCH)
            for (offset in fileData.limit() - 22 downTo start) {
                if (readInt(offset) == EOCD_SIGNATURE) {
                    return offset
                }
            }
            throw ZipException()
        }

        private fun matchesName(offset: Int, length: Int, expected: String): Boolean {
            if (length != expected.length) return false
            for (i in 0 until length) {
                if ((fileData.get(offset + i).toInt() and 0xff).toChar() != expected[i]) {
                    return false
                }
            }
            return true
        }

        private fun readInt(offset: Int): Int {
            return fileData.getInt(offset)
        }

        private fun readUnsignedShort(offset: Int): Int {
            return fileData.getShort(offset).toInt() and 0xffff
        }
    }

    private class DexReader(private val dex: ByteBuffer) {
        private val stringIdsSize: Int
        private val stringIdsOff: Int
        private val typeIdsSize: Int
        private val typeIdsOff: Int
        private val fieldIdsSize: Int
        private val fieldIdsOff: Int
        private val classDefsSize: Int
        private val classDefsOff: Int

        init {
            if (dex.limit() < 0x70 || readInt(0) != 0x0a786564) {
                throw IllegalArgumentException("Not a dex file")
            }
            stringIdsSize = readInt(0x38)
            stringIdsOff = readInt(0x3c)
            typeIdsSize = readInt(0x40)
            typeIdsOff = readInt(0x44)
            fieldIdsSize = readInt(0x50)
            fieldIdsOff = readInt(0x54)
            classDefsSize = readInt(0x60)
            classDefsOff = readInt(0x64)
        }

        fun scan(classes: MutableMap<String, DexClass>) {
            val wantedTypes = HashMap<Int, String>()
            addWantedType(classes, wantedTypes, CLASS)
            addWantedType(classes, wantedTypes, ACCESSIBLE_OBJECT)
            addWantedType(classes, wantedTypes, EXECUTABLE)
            addWantedType(classes, wantedTypes, METHOD_HANDLE)
            if (wantedTypes.isEmpty()) return

            var i = 0
            while (i < classDefsSize && wantedTypes.isNotEmpty()) {
                val offset = classDefsOff + i * 32
                val descriptor = wantedTypes.remove(readInt(offset))
                if (descriptor != null) {
                    val superclassIndex = readInt(offset + 8)
                    val superDescriptor = if (superclassIndex == NO_INDEX) OBJECT else getTypeDescriptor(superclassIndex)
                    val classDataOff = readInt(offset + 24)
                    classes[descriptor] = DexClass(superDescriptor, readInstanceFields(classDataOff))
                }
                i++
            }
        }

        private fun addWantedType(
            classes: Map<String, DexClass>,
            wantedTypes: MutableMap<Int, String>,
            descriptor: String
        ) {
            if (classes.containsKey(descriptor)) return
            val typeIndex = findTypeIndex(descriptor)
            if (typeIndex >= 0) wantedTypes[typeIndex] = descriptor
        }

        private fun readInstanceFields(offset: Int): ArrayList<DexField> {
            val fields = ArrayList<DexField>()
            if (offset == 0) return fields

            val position = Position(offset)
            val staticFieldsSize = readUleb128(position)
            val instanceFieldsSize = readUleb128(position)
            val directMethodsSize = readUleb128(position)
            val virtualMethodsSize = readUleb128(position)

            skipFields(position, staticFieldsSize)
            var fieldIndex = 0
            for (i in 0 until instanceFieldsSize) {
                fieldIndex += readUleb128(position)
                readUleb128(position)
                fields.add(readField(fieldIndex))
            }
            skipMethods(position, directMethodsSize + virtualMethodsSize)
            return fields
        }

        private fun skipFields(position: Position, count: Int) {
            for (i in 0 until count) {
                readUleb128(position)
                readUleb128(position)
            }
        }

        private fun skipMethods(position: Position, count: Int) {
            for (i in 0 until count) {
                readUleb128(position)
                readUleb128(position)
                readUleb128(position)
            }
        }

        private fun readField(fieldIndex: Int): DexField {
            if (fieldIndex !in 0..<fieldIdsSize) {
                throw IllegalArgumentException("Invalid field index $fieldIndex")
            }
            val offset = fieldIdsOff + fieldIndex * 8
            val typeIndex = readUnsignedShort(offset + 2)
            val nameIndex = readInt(offset + 4)
            return DexField(fieldIndex, getString(nameIndex), getTypeDescriptor(typeIndex))
        }

        private fun getTypeDescriptor(typeIndex: Int): String {
            if (typeIndex !in 0..<typeIdsSize) {
                throw IllegalArgumentException("Invalid type index $typeIndex")
            }
            return getString(readInt(typeIdsOff + typeIndex * 4))
        }

        private fun findTypeIndex(descriptor: String): Int {
            val descriptorIndex = findStringIndex(descriptor)
            if (descriptorIndex < 0) return -1

            var low = 0
            var high = typeIdsSize - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val actual = readInt(typeIdsOff + mid * 4)
                if (actual < descriptorIndex) {
                    low = mid + 1
                } else if (actual > descriptorIndex) {
                    high = mid - 1
                } else {
                    return mid
                }
            }
            return -1
        }

        private fun findStringIndex(expected: String): Int {
            var low = 0
            var high = stringIdsSize - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val compare = compareString(mid, expected)
                if (compare < 0) {
                    low = mid + 1
                } else if (compare > 0) {
                    high = mid - 1
                } else {
                    return mid
                }
            }
            return -1
        }

        private fun compareString(stringIndex: Int, expected: String): Int {
            if (stringIndex !in 0..<stringIdsSize) {
                throw IllegalArgumentException("Invalid string index $stringIndex")
            }
            val position = Position(readInt(stringIdsOff + stringIndex * 4))
            readUleb128(position)
            var expectedIndex = 0
            while (true) {
                val actual = readModifiedUtf8Char(position)
                if (actual == 0) {
                    return if (expectedIndex == expected.length) 0 else -1
                }
                if (expectedIndex == expected.length) {
                    return 1
                }
                val expectedChar = expected[expectedIndex++].code
                if (actual != expectedChar) {
                    return actual - expectedChar
                }
            }
        }

        private fun readModifiedUtf8Char(position: Position): Int {
            val current = dex.get(position.offset).toInt() and 0xff
            position.offset++
            if (current == 0 || (current and 0x80) == 0) {
                return current
            }
            if ((current and 0xe0) == 0xc0) {
                val next = dex.get(position.offset).toInt() and 0x3f
                position.offset++
                return (current and 0x1f shl 6) or next
            }
            val next1 = dex.get(position.offset).toInt() and 0x3f
            position.offset++
            val next2 = dex.get(position.offset).toInt() and 0x3f
            position.offset++
            return (current and 0x0f shl 12) or (next1 shl 6) or next2
        }

        private fun getString(stringIndex: Int): String {
            if (stringIndex !in 0..<stringIdsSize) {
                throw IllegalArgumentException("Invalid string index $stringIndex")
            }
            val position = Position(readInt(stringIdsOff + stringIndex * 4))
            readUleb128(position)
            val start = position.offset
            while (position.offset < dex.limit() && dex.get(position.offset).toInt() != 0) {
                position.offset++
            }
            return StandardCharsets.UTF_8.decode(slice(dex, start, position.offset - start)).toString()
        }

        private fun readUleb128(position: Position): Int {
            var result = 0
            var shift = 0
            var current: Int
            do {
                current = dex.get(position.offset).toInt() and 0xff
                position.offset++
                result = result or (current and 0x7f shl shift)
                shift += 7
            } while ((current and 0x80) != 0)
            return result
        }

        private fun readInt(offset: Int): Int {
            return dex.getInt(offset)
        }

        private fun readUnsignedShort(offset: Int): Int {
            return dex.getShort(offset).toInt() and 0xffff
        }
    }

    private class DexClass(val superDescriptor: String, val instanceFields: ArrayList<DexField>)

    private class DexField(val fieldIndex: Int, val name: String, val type: String) {
        val primitiveType: Char
            get() = if (type.length == 1) type[0] else '\u0000'

        val isPrimitive: Boolean
            get() = primitiveType != '\u0000'

        fun componentSize(): Int {
            return componentSize(primitiveType)
        }
    }

    class Layout(val objectSize: Int, private val offsets: Map<String, Int>) {
        fun hasField(name: String): Boolean {
            return offsets.containsKey(name)
        }

        @Throws(NoSuchFieldException::class)
        fun offsetOf(name: String): Int {
            return offsets[name] ?: throw NoSuchFieldException(name)
        }
    }

    private class FieldGap(val startOffset: Int, val size: Int)

    private class LinkState(var offset: Int) {
        var index = 0
    }

    private class Position(var offset: Int)

    companion object {
        val OBJECT: String = descriptorString(Any::class.java)
        val CLASS: String = descriptorString(Class::class.java)
        val ACCESSIBLE_OBJECT: String = descriptorString(AccessibleObject::class.java)
        val EXECUTABLE: String = descriptorString(Executable::class.java)
        val METHOD_HANDLE: String = descriptorString(MethodHandle::class.java)

        private const val OBJECT_HEADER_SIZE = 8
        private const val REFERENCE_SIZE = 4

        private const val EOCD_SIGNATURE = 0x06054b50
        private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50
        private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50
        private const val STORED = 0
        private const val MAX_EOCD_SEARCH = 0xffff + 22
        private const val NO_INDEX = -1

        private val FIELD_COMPARATOR = Comparator<DexField> { field1, field2 ->
            val type1 = field1.primitiveType
            val type2 = field2.primitiveType
            if (type1 != type2) {
                if (type1 == '\u0000') return@Comparator -1
                if (type2 == '\u0000') return@Comparator 1
                val size1 = componentSize(type1)
                val size2 = componentSize(type2)
                if (size1 != size2) return@Comparator size2 - size1
                return@Comparator primitiveOrder(type1) - primitiveOrder(type2)
            }
            field1.fieldIndex - field2.fieldIndex
        }

        private val FIELD_GAP_COMPARATOR = Comparator<FieldGap> { gap1, gap2 ->
            if (gap1.size != gap2.size) return@Comparator gap2.size - gap1.size
            gap1.startOffset - gap2.startOffset
        }

        private fun descriptorString(clazz: Class<*>): String {
            return 'L' + clazz.name.replace('.', '/') + ';'
        }

        private fun slice(buffer: ByteBuffer, offset: Int, size: Int): ByteBuffer {
            val duplicate = buffer.duplicate()
            duplicate.position(offset)
            duplicate.limit(offset + size)
            return duplicate.slice().order(buffer.order())
        }

        private fun isAligned(value: Int, alignment: Int): Boolean {
            return (value and (alignment - 1)) == 0
        }

        private fun roundUp(value: Int, alignment: Int): Int {
            return value + alignment - 1 and -alignment
        }

        private fun primitiveOrder(type: Char): Int {
            return when (type) {
                'Z' -> 1
                'B' -> 2
                'C' -> 3
                'S' -> 4
                'I' -> 5
                'J' -> 6
                'F' -> 7
                'D' -> 8
                else -> 0
            }
        }

        private fun componentSize(type: Char): Int {
            return when (type) {
                'J', 'D' -> 8
                'I', 'F' -> 4
                'C', 'S' -> 2
                'Z', 'B' -> 1
                else -> REFERENCE_SIZE
            }
        }
    }
}
