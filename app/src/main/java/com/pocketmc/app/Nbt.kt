package com.pocketmc.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// Minimal NBT okuma/yazma (level.dat ve playerdata için)
class NTag(val id: Int, var v: Any?)
class NList(val type: Int, val items: List<Any?>)

object Nbt {
    fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
    fun gzip(b: ByteArray): ByteArray { val o = ByteArrayOutputStream(); GZIPOutputStream(o).use { it.write(b) }; return o.toByteArray() }

    fun parse(raw: ByteArray): NTag {
        val i = DataInputStream(ByteArrayInputStream(raw))
        if (i.readByte().toInt() != 10) throw IOException("NBT kökü compound değil")
        i.readUTF()
        return NTag(10, read(i, 10))
    }

    fun write(root: NTag): ByteArray {
        val bo = ByteArrayOutputStream(); val o = DataOutputStream(bo)
        o.writeByte(10); o.writeUTF(""); put(o, 10, root.v); o.flush()
        return bo.toByteArray()
    }

    private fun read(i: DataInputStream, id: Int): Any? = when (id) {
        1 -> i.readByte()
        2 -> i.readShort()
        3 -> i.readInt()
        4 -> i.readLong()
        5 -> i.readFloat()
        6 -> i.readDouble()
        7 -> ByteArray(i.readInt()).also { i.readFully(it) }
        8 -> i.readUTF()
        9 -> { val t = i.readByte().toInt(); val n = i.readInt(); NList(t, List(n) { read(i, t) }) }
        10 -> {
            val m = LinkedHashMap<String, NTag>()
            while (true) {
                val t = i.readByte().toInt(); if (t == 0) break
                val name = i.readUTF(); m[name] = NTag(t, read(i, t))
            }
            m
        }
        11 -> IntArray(i.readInt()) { i.readInt() }
        12 -> LongArray(i.readInt()) { i.readLong() }
        else -> throw IOException("Bilinmeyen NBT türü $id")
    }

    @Suppress("UNCHECKED_CAST")
    private fun put(o: DataOutputStream, id: Int, v: Any?) {
        when (id) {
            1 -> o.writeByte((v as Byte).toInt())
            2 -> o.writeShort((v as Short).toInt())
            3 -> o.writeInt(v as Int)
            4 -> o.writeLong(v as Long)
            5 -> o.writeFloat(v as Float)
            6 -> o.writeDouble(v as Double)
            7 -> { val a = v as ByteArray; o.writeInt(a.size); o.write(a) }
            8 -> o.writeUTF(v as String)
            9 -> { val l = v as NList; o.writeByte(l.type); o.writeInt(l.items.size); l.items.forEach { put(o, l.type, it) } }
            10 -> {
                for ((k, t) in v as Map<String, NTag>) { o.writeByte(t.id); o.writeUTF(k); put(o, t.id, t.v) }
                o.writeByte(0)
            }
            11 -> { val a = v as IntArray; o.writeInt(a.size); a.forEach { o.writeInt(it) } }
            12 -> { val a = v as LongArray; o.writeInt(a.size); a.forEach { o.writeLong(it) } }
            else -> throw IOException("Bilinmeyen NBT türü $id")
        }
    }
}
