/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.airframe.msgpack.spi

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale

import wvlet.airframe.msgpack.spi.ErrorCode.{INVALID_EXT_FORMAT, INVALID_TYPE, NEVER_USED_FORMAT}
import wvlet.airframe.msgpack.spi.Value._

import Unpacker._
import MessageException._

/**
  * Read a message pack data from a given offset in the buffer. The last read byte length can be checked by calling [[lastReadByteLength]] method.
  */
class Unpacker {
  private var _lastReadByteLength: Int = 0

  def lastReadByteLength: Int = _lastReadByteLength

  def unpackValue(buf: ReadBuffer, position: Int): Value = {
    val b  = buf.readByte(position)
    val mf = MessageFormat.of(b)
    mf.valueType match {
      case ValueType.NIL =>
        _lastReadByteLength = 1
        NilValue
      case ValueType.BOOLEAN =>
        Value.BooleanValue(unpackBoolean(buf, position))
      case ValueType.INTEGER =>
        mf match {
          case MessageFormat.UINT64 =>
            BigIntegerValue(unpackBigInteger(buf, position))
          case _ =>
            LongValue(unpackLong(buf, position))
        }
      case ValueType.FLOAT =>
        DoubleValue(unpackDouble(buf, position))
      case ValueType.STRING =>
        StringValue(unpackString(buf, position))
      case ValueType.BINARY =>
        var readLen      = 0
        val binaryLength = unpackBinaryHeader(buf, position)
        readLen += _lastReadByteLength
        _lastReadByteLength = 0 // Reset here because readPayload may thrown an exception
        val data = readPayload(buf, position + readLen, binaryLength)
        readLen += _lastReadByteLength
        _lastReadByteLength = readLen
        BinaryValue(data)
      case ValueType.EXTENSION =>
        var readLen   = 0
        val extHeader = unpackExtTypeHeader(buf, position)
        readLen += _lastReadByteLength
        _lastReadByteLength = 0
        if (extHeader.extType == Code.EXT_TIMESTAMP) {
          val instant = unpackTimestampInternal(extHeader, buf, position + readLen)
          _lastReadByteLength += readLen
          TimestampValue(instant)
        } else {
          val extData = readPayload(buf, position + readLen, extHeader.byteLength)
          _lastReadByteLength += readLen
          ExtensionValue(extHeader.extType, extData)
        }
      case ValueType.ARRAY =>
        var readLen     = 0
        val arrayLength = unpackArrayHeader(buf, position)
        readLen += _lastReadByteLength
        _lastReadByteLength = 0 // Reset here so as not to move cursor in the middle of reading
        val arr = IndexedSeq.newBuilder[Value]
        arr.sizeHint(arrayLength)
        var i = 0
        while (i < arrayLength) {
          arr += unpackValue(buf, position + readLen)
          readLen += _lastReadByteLength
          _lastReadByteLength = 0
          i += 1
        }
        _lastReadByteLength = readLen
        ArrayValue(arr.result())
      case ValueType.MAP =>
        var readLen   = 0
        val mapLength = unpackMapHeader(buf, position)
        readLen += _lastReadByteLength
        _lastReadByteLength = 0 // Reset here so as not to move cursor in the middle of reading
        val map = Map.newBuilder[Value, Value]
        map.sizeHint(mapLength)
        var i = 0
        while (i < mapLength) {
          val key = unpackValue(buf, position + readLen)
          readLen += _lastReadByteLength
          _lastReadByteLength = 0
          val value = unpackValue(buf, position + readLen)
          readLen += _lastReadByteLength
          map += (key -> value)
          i += 1
        }
        _lastReadByteLength = readLen
        MapValue(map.result)
    }
  }

  def unpackNil(buf: ReadBuffer, position: Int) {
    buf.readByte(position) match {
      case Code.NIL =>
        _lastReadByteLength = 1
      case other => unexpected("nil", other)
    }
  }

  def tryUnpackNil(buf: ReadBuffer, position: Int): Boolean = {
    buf.readByte(position) match {
      case Code.NIL =>
        _lastReadByteLength = 1
        true
      case other =>
        _lastReadByteLength = 0
        false
    }
  }

  def unpackBoolean(buf: ReadBuffer, position: Int): Boolean = {
    buf.readByte(position) match {
      case Code.FALSE =>
        _lastReadByteLength = 1
        false
      case Code.TRUE =>
        _lastReadByteLength = 1
        true
      case other => unexpected("boolean", other)
    }
  }

  def unpackByte(buf: ReadBuffer, position: Int): Byte = {
    val b = buf.readByte(position)
    b match {
      case b if Code.isFixInt(b) =>
        _lastReadByteLength = 1
        b
      case Code.UINT8 =>
        val u8 = buf.readByte(position + 1)
        if (u8 < 0) throw overflowU8(u8)
        _lastReadByteLength = 2
        u8
      case Code.UINT16 =>
        val u16 = buf.readShort(position + 1)
        if (u16 < 0 || !u16.isValidByte) throw overflowU16(u16)
        _lastReadByteLength = 3
        u16.toByte
      case Code.UINT32 =>
        val u32 = buf.readInt(position + 1)
        if (u32 < 0 || !u32.isValidByte) throw overflowU32(u32)
        _lastReadByteLength = 5
        u32.toByte
      case Code.UINT64 =>
        val u64 = buf.readLong(position + 1)
        if (u64 < 0 || !u64.isValidByte) throw overflowU64(u64)
        _lastReadByteLength = 9
        u64.toByte
      case Code.INT8 =>
        val i8 = buf.readByte(position + 1)
        _lastReadByteLength = 2
        i8
      case Code.INT16 =>
        val i16 = buf.readShort(position + 1)
        if (!i16.isValidByte) throw overflowI16(i16)
        _lastReadByteLength = 3
        i16.toByte
      case Code.INT32 =>
        val i32 = buf.readInt(position + 1)
        if (!i32.isValidByte) throw overflowI32(i32)
        _lastReadByteLength = 5
        i32.toByte
      case Code.INT64 =>
        val i64 = buf.readLong(position + 1)
        if (!i64.isValidByte) throw overflowI64(i64)
        _lastReadByteLength = 9
        i64.toByte
      case _ =>
        unexpected("Integer", b)
    }
  }

  def unpackShort(buf: ReadBuffer, position: Int): Short = {
    val b = buf.readByte(position)
    b match {
      case b if Code.isFixInt(b) =>
        _lastReadByteLength = 1
        b.toShort
      case Code.UINT8 =>
        val u8 = buf.readByte(position + 1)
        _lastReadByteLength = 2
        (u8 & 0xff).toShort
      case Code.UINT16 =>
        val u16 = buf.readShort(position + 1)
        if (u16 < 0) throw overflowU16(u16)
        _lastReadByteLength = 3
        u16.toShort
      case Code.UINT32 =>
        val u32 = buf.readInt(position + 1)
        if (u32 < 0 || !u32.isValidShort) throw overflowU32(u32)
        _lastReadByteLength = 5
        u32.toShort
      case Code.UINT64 =>
        val u64 = buf.readLong(position + 1)
        if (u64 < 0 || !u64.isValidShort) throw overflowU64(u64)
        _lastReadByteLength = 9
        u64.toShort
      case Code.INT8 =>
        val i8 = buf.readByte(position + 1)
        _lastReadByteLength = 2
        i8.toShort
      case Code.INT16 =>
        val i16 = buf.readShort(position + 1)
        _lastReadByteLength = 3
        i16.toShort
      case Code.INT32 =>
        val i32 = buf.readInt(position + 1)
        if (!i32.isValidShort) throw overflowI32(i32)
        _lastReadByteLength = 5
        i32.toShort
      case Code.INT64 =>
        val i64 = buf.readLong(position + 1)
        if (!i64.isValidShort) throw overflowI64(i64)
        _lastReadByteLength = 9
        i64.toShort
      case _ =>
        unexpected("Integer", b)
    }
  }

  def unpackInt(buf: ReadBuffer, position: Int): Int = {
    val b = buf.readByte(position)
    b match {
      case b if Code.isFixInt(b) =>
        _lastReadByteLength = 1
        b.toInt
      case Code.UINT8 =>
        val u8 = buf.readByte(position + 1)
        _lastReadByteLength = 2
        u8 & 0xff
      case Code.UINT16 =>
        val u16 = buf.readShort(position + 1)
        if (u16 < 0) throw overflowU16(u16)
        _lastReadByteLength = 3
        u16 & 0xffff
      case Code.UINT32 =>
        val u32 = buf.readInt(position + 1)
        if (u32 < 0) throw overflowU32(u32)
        _lastReadByteLength = 5
        u32
      case Code.UINT64 =>
        val u64 = buf.readLong(position + 1)
        if (u64 < 0 || !u64.isValidInt) throw overflowU64(u64)
        _lastReadByteLength = 9
        u64.toInt
      case Code.INT8 =>
        val i8 = buf.readByte(position + 1)
        _lastReadByteLength = 2
        i8.toInt
      case Code.INT16 =>
        val i16 = buf.readShort(position + 1)
        _lastReadByteLength = 3
        i16.toInt
      case Code.INT32 =>
        val i32 = buf.readInt(position + 1)
        _lastReadByteLength = 5
        i32.toInt
      case Code.INT64 =>
        val i64 = buf.readLong(position + 1)
        if (!i64.isValidInt) throw overflowI64(i64)
        _lastReadByteLength = 9
        i64.toInt
      case _ =>
        unexpected("Integer", b)
    }
  }

  def unpackLong(buf: ReadBuffer, position: Int): Long = {
    val b = buf.readByte(position)
    b match {
      case b if Code.isFixInt(b) =>
        _lastReadByteLength = 1
        b.toLong
      case Code.UINT8 =>
        val u8 = buf.readByte(position + 1)
        _lastReadByteLength = 2
        (u8 & 0xff).toLong
      case Code.UINT16 =>
        val u16 = buf.readShort(position + 1)
        if (u16 < 0) throw overflowU16(u16)
        _lastReadByteLength = 3
        (u16 & 0xffff).toLong
      case Code.UINT32 =>
        val u32 = buf.readInt(position + 1)
        _lastReadByteLength = 5
        if (u32 < 0) {
          (u32 & 0x7fffffff).toLong + 0x80000000L
        } else {
          u32.toLong
        }
      case Code.UINT64 =>
        val u64 = buf.readLong(position + 1)
        if (u64 < 0) throw overflowU64(u64)
        _lastReadByteLength = 9
        u64.toLong
      case Code.INT8 =>
        val i8 = buf.readByte(position + 1)
        _lastReadByteLength = 2
        i8.toLong
      case Code.INT16 =>
        val i16 = buf.readShort(position + 1)
        _lastReadByteLength = 3
        i16.toLong
      case Code.INT32 =>
        val i32 = buf.readInt(position + 1)
        _lastReadByteLength = 5
        i32.toLong
      case Code.INT64 =>
        val i64 = buf.readLong(position + 1)
        _lastReadByteLength = 9
        i64
      case _ =>
        unexpected("Integer", b)
    }
  }

  def unpackBigInteger(buf: ReadBuffer, position: Int): BigInteger = {
    val b = buf.readByte(position)
    b match {
      case b if Code.isFixInt(b) =>
        _lastReadByteLength = 1
        BigInteger.valueOf(b.toLong)
      case Code.UINT8 =>
        val u8 = buf.readByte(position + 1)
        _lastReadByteLength = 2
        BigInteger.valueOf((u8 & 0xff).toLong)
      case Code.UINT16 =>
        val u16 = buf.readShort(position + 1)
        if (u16 < 0) throw overflowU16(u16)
        _lastReadByteLength = 3
        BigInteger.valueOf((u16 & 0xffff).toLong)
      case Code.UINT32 =>
        val u32 = buf.readInt(position + 1)
        _lastReadByteLength = 5
        if (u32 < 0) {
          BigInteger.valueOf((u32 & 0x7fffffff).toLong + 0x80000000L)
        } else {
          BigInteger.valueOf(u32.toLong)
        }
      case Code.UINT64 =>
        val u64 = buf.readLong(position + 1)
        if (u64 < 0) throw overflowU64(u64)
        _lastReadByteLength = 9
        BigInteger.valueOf(u64.toLong)
      case Code.INT8 =>
        val i8 = buf.readByte(position + 1)
        _lastReadByteLength = 2
        BigInteger.valueOf(i8.toLong)
      case Code.INT16 =>
        val i16 = buf.readShort(position + 1)
        _lastReadByteLength = 3
        BigInteger.valueOf(i16.toLong)
      case Code.INT32 =>
        val i32 = buf.readInt(position + 1)
        _lastReadByteLength = 5
        BigInteger.valueOf(i32.toLong)
      case Code.INT64 =>
        val i64 = buf.readLong(position + 1)
        _lastReadByteLength = 9
        BigInteger.valueOf(i64)
      case _ =>
        unexpected("Integer", b)
    }
  }

  def unpackFloat(buf: ReadBuffer, position: Int): Float = {
    buf.readByte(position) match {
      case Code.FLOAT32 =>
        val f = buf.readFloat(position + 1)
        _lastReadByteLength = 5
        f
      case Code.FLOAT64 =>
        val d = buf.readDouble(position + 1)
        _lastReadByteLength = 9
        d.toFloat
      case other =>
        throw unexpected("Float", other)
    }
  }

  def unpackDouble(buf: ReadBuffer, position: Int): Double = {
    buf.readByte(position) match {
      case Code.FLOAT32 =>
        val f = buf.readFloat(position + 1)
        _lastReadByteLength = 5
        f.toDouble
      case Code.FLOAT64 =>
        val d = buf.readDouble(position + 1)
        _lastReadByteLength = 9
        d
      case other =>
        throw unexpected("Float", other)
    }
  }

  private def readNextLength8(buf: ReadBuffer, position: Int): Int = {
    val u8 = buf.readByte(position)
    _lastReadByteLength = 1
    u8 & 0xff
  }

  private def readNextLength16(buf: ReadBuffer, position: Int): Int = {
    val u16 = buf.readShort(position)
    _lastReadByteLength = 2
    u16 & 0xffff
  }

  private def readNextLength32(buf: ReadBuffer, position: Int): Int = {
    val u32 = buf.readInt(position)
    if (u32 < 0) throw overflowU32Size(u32)
    _lastReadByteLength = 4
    u32
  }

  private def tryReadStringHeader(b: Byte, buf: ReadBuffer, position: Int) = b match {
    case Code.STR8 =>
      readNextLength8(buf, position)
    case Code.STR16 =>
      readNextLength16(buf, position)
    case Code.STR32 =>
      readNextLength32(buf, position)
    case _ =>
      -1
  }

  private def tryReadBinaryHeader(b: Byte, buf: ReadBuffer, position: Int) = b match {
    case Code.BIN8 => // bin 8
      readNextLength8(buf, position)
    case Code.BIN16 => // bin 16
      readNextLength16(buf, position)
    case Code.BIN32 => // bin 32
      readNextLength32(buf, position)
    case _ =>
      -1
  }

  def unpackRawStringHeader(buf: ReadBuffer, position: Int): Int = {
    val b = buf.readByte(position)
    if (Code.isFixedRaw(b)) {
      b & 0x1f
    } else {
      val slen = tryReadStringHeader(b, buf, position + 1)
      if (slen >= 0) {
        // Add the first byte count
        _lastReadByteLength = _lastReadByteLength + 1
        slen
      } else {
        val blen = tryReadBinaryHeader(b, buf, position + 1)
        if (blen >= 0) {
          // Ad the first byte count
          _lastReadByteLength = _lastReadByteLength + 1
          blen
        }
      }
    }
    throw unexpected("String", b)
  }

  def unpackBinaryHeader(buf: ReadBuffer, position: Int): Int = {
    val b = buf.readByte(position)
    if (Code.isFixedRaw(b)) {
      b & 0x1f
    } else {
      val blen = tryReadBinaryHeader(b, buf, position + 1)
      if (blen >= 0) {
        // Add the first byte count
        _lastReadByteLength = _lastReadByteLength + 1
        blen
      } else {
        val slen = tryReadStringHeader(b, buf, position + 1)
        if (slen >= 0) {
          // Ad the first byte count
          _lastReadByteLength = _lastReadByteLength + 1
          slen
        }
      }
    }
    throw unexpected("Binary", b)
  }

  def unpackString(buf: ReadBuffer, position: Int): String = {
    val len       = unpackRawStringHeader(buf, position)
    val headerLen = _lastReadByteLength
    val nextIndex = position + headerLen
    if (len == 0) {
      EMPTY_STRING
    } else if (len >= Integer.MAX_VALUE) {
      throw new TooLargeMessageException(len)
    } else {
      // TODO reduce memory copy (e.g., by getting a memory reference of the Buffer)
      val str = buf.readBytes(nextIndex, len)
      _lastReadByteLength = headerLen + len
      new String(str, 0, str.length, StandardCharsets.UTF_8)
    }
  }

  def unpackArrayHeader(buf: ReadBuffer, position: Int): Int = {
    val b = buf.readByte(position)
    b match {
      case b if Code.isFixedArray(b) =>
        _lastReadByteLength = 1
        b & 0x0f
      case Code.ARRAY16 =>
        val len = readNextLength16(buf, position + 1)
        _lastReadByteLength += 1
        len
      case Code.ARRAY32 =>
        val len = readNextLength32(buf, position + 1)
        _lastReadByteLength += 1
        len
      case _ =>
        throw unexpected("Array", b)
    }
  }

  def unpackMapHeader(buf: ReadBuffer, position: Int): Int = {
    val b = buf.readByte(position)
    b match {
      case b if Code.isFixedMap(b) =>
        _lastReadByteLength = 1
        b & 0x0f
      case Code.MAP16 =>
        val len = readNextLength16(buf, position + 1)
        _lastReadByteLength += 1
        len
      case Code.MAP32 =>
        val len = readNextLength32(buf, position + 1)
        _lastReadByteLength += 1
        len
      case _ =>
        throw unexpected("Map", b)
    }
  }

  def unpackExtTypeHeader(buf: ReadBuffer, position: Int): ExtTypeHeader = {
    buf.readByte(position) match {
      case Code.FIXEXT1 =>
        val tpe = buf.readByte(position + 1)
        _lastReadByteLength = 2
        ExtTypeHeader(tpe, 1);
      case Code.FIXEXT2 =>
        val tpe = buf.readByte(position + 1)
        _lastReadByteLength = 2
        ExtTypeHeader(tpe, 2);
      case Code.FIXEXT4 =>
        val tpe = buf.readByte(position + 1)
        _lastReadByteLength = 2
        ExtTypeHeader(tpe, 4);
      case Code.FIXEXT8 =>
        val tpe = buf.readByte(position + 1)
        _lastReadByteLength = 2
        ExtTypeHeader(tpe, 8);
      case Code.FIXEXT16 =>
        val tpe = buf.readByte(position + 1)
        _lastReadByteLength = 2
        ExtTypeHeader(tpe, 16);
      case Code.EXT8 =>
        val u8  = buf.readByte(position + 1)
        val len = u8 & 0xff
        val tpe = buf.readByte(position + 2)
        _lastReadByteLength = 3
        ExtTypeHeader(tpe, len)
      case Code.EXT16 =>
        val u16 = buf.readShort(position + 1)
        val len = u16 & 0xffff
        val tpe = buf.readByte(position + 3)
        _lastReadByteLength = 4
        ExtTypeHeader(tpe, len)
      case Code.EXT32 =>
        val u32 = buf.readInt(position + 1)
        if (u32 < 0) throw overflowU32Size(u32)
        val tpe = buf.readByte(position + 5)
        _lastReadByteLength = 6
        ExtTypeHeader(tpe, u32)
      case other =>
        throw unexpected("Extension", other)
    }
  }

  def unpackTimestamp(buf: ReadBuffer, position: Int): Instant = {
    val extTypeHeader = unpackExtTypeHeader(buf, position)
    val readLen       = _lastReadByteLength
    _lastReadByteLength = 0 // Need to reset the intermediate read length
    val instant = unpackTimestampInternal(extTypeHeader, buf, position + readLen)
    _lastReadByteLength = readLen + _lastReadByteLength
    instant
  }

  private def unpackTimestampInternal(extTypeHeader: ExtTypeHeader, buf: ReadBuffer, position: Int): Instant = {
    var readLen = 0
    if (extTypeHeader.extType != Code.EXT_TIMESTAMP) {
      throw unexpected("Timestamp", extTypeHeader.extType)
    }
    val instant = extTypeHeader.byteLength match {
      case 4 =>
        val u32 = buf.readInt(position + readLen)
        readLen += 4
        Instant.ofEpochSecond(u32)
      case 8 =>
        val d64 = buf.readLong(position + readLen)
        readLen += 8
        val sec  = d64 & 0x00000003ffffffffL
        val nsec = (d64 >>> 34).toInt
        Instant.ofEpochSecond(sec, nsec)
      case 12 =>
        val nsec = buf.readInt(position + readLen)
        readLen += 4
        val sec = buf.readLong(position + readLen)
        readLen += 8
        Instant.ofEpochSecond(sec, nsec)
      case other =>
        throw new MessageException(INVALID_EXT_FORMAT, s"Timestamp type expects 4, 8, or 12 bytes of payload but got ${other} bytes")
    }
    _lastReadByteLength = readLen
    instant
  }

  def readPayload(buf: ReadBuffer, position: Int, length: Int): Array[Byte] = {
    val data = buf.readBytes(position, length)
    _lastReadByteLength = length
    data
  }

  /**
    * Read a payload of the given length from the given buffer[position], and write the result to the destination buffer.
    *
    * @param buf
    * @param position
    * @param length
    * @param dest
    * @param destIndex
    * @return A slice (shallow copy) of the destination buffer
    */
  def readPayload(buf: ReadBuffer, position: Int, length: Int, dest: WriteBuffer, destIndex: Int): ReadBuffer = {
    buf.readBytes(position, length, dest, destIndex)
    _lastReadByteLength = length
    dest.slice(destIndex, length)
  }

}

object Unpacker {
  val EMPTY_STRING: String = ""

  private[spi] def unexpected(expectedCode: String, actual: Byte) = {
    val f = MessageFormat.of(actual)
    if (f == MessageFormat.NEVER_USED) {
      throw new MessageException(NEVER_USED_FORMAT, s"Expected ${expectedCode}, but found 0xC1 (NEVER_USED) byte")
    } else {
      val name     = f.valueType.name
      val typeName = name.substring(0, 1) + name.substring(1).toLowerCase(Locale.ENGLISH)
      throw new MessageException(INVALID_TYPE, f"Expected ${expectedCode}, but got ${typeName} (${actual}%02x)")
    }
  }
}
