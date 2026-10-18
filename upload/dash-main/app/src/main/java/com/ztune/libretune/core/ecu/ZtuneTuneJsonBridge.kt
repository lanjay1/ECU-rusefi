package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.DataType
import com.ztune.libretune.core.ini.types.Shape
import com.ztune.libretune.core.tune.ByteOrderReader
import com.ztune.libretune.core.tune.ByteOrderWriter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jembatan JSON <-> byte-page untuk [ZtuneEcu].
 *
 * Kontrak JSON (key = nama constant PERSIS seperti di ztune.ini, nilai TERSKALA):
 *  - skalar   : angka (true/false dianggap 1/0)
 *  - array 1D : [v0, v1, ...]          panjang = ukuran array di INI
 *  - array 2D : [[...], [...], ...]    baris x kolom sesuai INI (baris = sumbu Y)
 * Bins, tabel, dan kurva semuanya constant bertipe array, jadi satu jalur kode
 * menangani semuanya. Key JSON yang tidak dikenal diabaikan (forward-compat).
 * Pelanggaran kontrak (panjang array salah, nilai di luar range tipe) = exception
 * yang menyebut nama field.
 */
object ZtuneTuneJsonBridge {

    /**
     * Encode tune JSON menjadi buffer byte-page sepanjang [bufferSize]; tiap
     * constant ditulis di offset-nya. Constant yang tidak muat penuh di dalam
     * buffer (window parsial) dilewati.
     */
    fun jsonToPageBytes(
        definition: EcuDefinition,
        page: Int,
        bufferSize: Int,
        json: JSONObject
    ): ByteArray {
        val bytes = ByteArray(bufferSize)

        for ((name, c) in definition.constants) {
            if (c.page != page || c.isPcVariable || c.dataType == DataType.STRING) continue
            if (!json.has(name) || json.isNull(name)) continue

            val elem = c.dataType.byteSize
            if (c.offset < 0 || c.offset + elem * elementCount(c.shape) > bufferSize) continue

            when (val shape = c.shape) {
                is Shape.Array2D -> {
                    val rows = json.getJSONArray(name)
                    require(rows.length() == shape.rows) {
                        "'$name': jumlah baris ${rows.length()} != ${shape.rows}"
                    }
                    for (r in 0 until shape.rows) {
                        val row = rows.getJSONArray(r)
                        require(row.length() == shape.cols) {
                            "'$name' baris $r: jumlah kolom ${row.length()} != ${shape.cols}"
                        }
                        for (col in 0 until shape.cols) {
                            writeScalar(
                                bytes, definition, c.offset + (r * shape.cols + col) * elem,
                                c.dataType, c.scale, c.translate, number(row.get(col), name), name
                            )
                        }
                    }
                }
                is Shape.Array1D -> {
                    val arr = json.getJSONArray(name)
                    require(arr.length() == shape.size) {
                        "'$name': panjang ${arr.length()} != ${shape.size}"
                    }
                    for (i in 0 until shape.size) {
                        writeScalar(
                            bytes, definition, c.offset + i * elem,
                            c.dataType, c.scale, c.translate, number(arr.get(i), name), name
                        )
                    }
                }
                else -> writeScalar(
                    bytes, definition, c.offset,
                    c.dataType, c.scale, c.translate, number(json.get(name), name), name
                )
            }
        }
        return bytes
    }

    /**
     * Decode [pageBytes] (mewakili byte absolut [bufferOffset, bufferOffset + size)
     * pada [page]) menjadi JSON PARSIAL berisi constant yang seluruh byte-nya
     * ada di dalam window itu.
     */
    fun pageBytesToPartialJson(
        definition: EcuDefinition,
        page: Int,
        bufferOffset: Int,
        pageBytes: ByteArray
    ): JSONObject {
        val out = JSONObject()
        val reader = ByteOrderReader(pageBytes, definition.endianness)
        val windowEnd = bufferOffset + pageBytes.size

        for ((name, c) in definition.constants) {
            if (c.page != page || c.isPcVariable || c.dataType == DataType.STRING) continue
            val elem = c.dataType.byteSize
            val total = elem * elementCount(c.shape)
            if (c.offset < bufferOffset || c.offset + total > windowEnd) continue

            fun read(i: Int): Double {
                val raw = reader.readValueAt(c.offset - bufferOffset + i * elem, c.dataType)
                    ?: throw IllegalStateException("Gagal membaca '$name'[$i] dari buffer")
                return round6(raw * c.scale + c.translate)
            }

            when (val shape = c.shape) {
                is Shape.Array2D -> {
                    val rows = JSONArray()
                    for (r in 0 until shape.rows) {
                        val row = JSONArray()
                        for (col in 0 until shape.cols) row.put(read(r * shape.cols + col))
                        rows.put(row)
                    }
                    out.put(name, rows)
                }
                is Shape.Array1D -> {
                    val arr = JSONArray()
                    for (i in 0 until shape.size) arr.put(read(i))
                    out.put(name, arr)
                }
                else -> out.put(name, read(0))
            }
        }
        return out
    }

    private fun elementCount(shape: Shape): Int = when (shape) {
        is Shape.Array2D -> shape.rows * shape.cols
        is Shape.Array1D -> shape.size
        else -> 1
    }

    private fun number(v: Any?, field: String): Double = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        else -> throw IllegalArgumentException("Nilai '$field' bukan angka: $v")
    }

    private fun round6(x: Double): Double = Math.round(x * 1e6) / 1e6

    private fun writeScalar(
        bytes: ByteArray,
        definition: EcuDefinition,
        offset: Int,
        dataType: DataType,
        scale: Double,
        translate: Double,
        displayValue: Double,
        fieldName: String
    ) {
        require(scale != 0.0) { "writeScalar: scale == 0 untuk field '$fieldName'" }
        val raw = (displayValue - translate) / scale
        require(dataType.isInRange(raw)) {
            "writeScalar: nilai $displayValue (raw $raw) di luar range " +
                "${dataType.name} [${dataType.rawMin}, ${dataType.rawMax}] untuk field '$fieldName'"
        }
        val writer = ByteOrderWriter(dataType.byteSize, definition.endianness)
        writer.writeValue(dataType, raw)
        val encoded = writer.toByteArray()
        val end = offset + encoded.size
        require(offset >= 0 && end <= bytes.size) {
            "writeScalar: field '$fieldName' (${dataType.byteSize}B @ $offset) " +
                "overflow terhadap buffer ${bytes.size}B"
        }
        System.arraycopy(encoded, 0, bytes, offset, encoded.size)
    }
}