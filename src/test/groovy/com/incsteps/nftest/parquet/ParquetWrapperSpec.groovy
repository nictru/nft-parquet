package com.incsteps.nftest.parquet

import groovy.json.JsonOutput
import com.jerolba.carpet.CarpetParquetWriter
import org.apache.parquet.hadoop.ParquetFileWriter
import org.apache.parquet.hadoop.metadata.ParquetMetadata
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.format.converter.ParquetMetadataConverter
import org.apache.parquet.format.Util
import org.apache.parquet.io.LocalInputFile
import org.apache.parquet.io.LocalOutputFile
import org.apache.parquet.schema.MessageTypeParser
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ParquetWrapperSpec extends Specification {
    @TempDir Path directory
    File file
    ParquetWrapper table

    def setup() {
        file = directory.resolve('DE results:case.parquet').toFile()
        Fixtures.write(file, false)
        table = new ParquetWrapper(file, [:])
    }

    def 'footer exposes ordered columns, native row count and nullability'() {
        expect:
        table.rowCount == 3L
        table.rowCount() instanceof Long
        table.columnNames == ['gene', 'log2fc', 'pvalue', 'padj', 'group', 'contrast', 'stratum', 'significant']
        table.schema.fields.find { it.name == 'log2fc' }.physicalType == 'DOUBLE'
        table.schema.fields.every { it.nullable && it.repetition == 'OPTIONAL' }
        table.schema.fields.find { it.name == 'gene' }.logicalType == [type: 'STRING']
        table.rowGroupCount == 1
        table.compressionCodecs.size() == 1
        table.rowGroups[0].rowCount == 3L
        table.rowGroups[0].columns*.path == table.columnNames.collect { [it] }
        JsonOutput.toJson(table.schema).contains('log2fc')
    }

    def 'DE row maps preserve numeric boolean string null NaN and infinity values'() {
        when:
        def rows = table.rows
        def row = rows.find { it.gene == 'GENE1' && it.contrast == 'treated-control' }

        then:
        row != null
        row.log2fc instanceof Double
        Math.abs(row.log2fc - 1.25d) < 1e-10
        row.significant == true
        rows[1].significant == false
        rows[1].containsKey('log2fc')
        rows[1].log2fc == null
        Double.isNaN(rows[1].pvalue)
        rows[1].padj == Double.POSITIVE_INFINITY
        rows[2].log2fc == Double.NEGATIVE_INFINITY
        rows[2].padj == null
        rows[2].significant == null
        rows[2].stratum == 'α β'
    }

    def 'footer counts do not overflow an integer or decode row data'() {
        given:
        ParquetMetadata metadata
        try (def reader = ParquetFileReader.open(new LocalInputFile(file.toPath()))) {
            metadata = reader.footer
        }
        metadata.blocks[0].rowCount = 3_000_000_000L
        def footer = new ByteArrayOutputStream()
        Util.writeFileMetaData(new ParquetMetadataConverter().toParquetMetadata(1, metadata), footer)
        // This metadata-only fixture intentionally has no row pages. Counting
        // must only inspect the footer, not attempt to deserialize any rows.
        try (def output = new FileOutputStream(file)) {
            output.write('PAR1'.bytes)
            output.write(footer.toByteArray())
            output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(footer.size()).array())
            output.write('PAR1'.bytes)
        }

        expect:
        table.rowCount == 3_000_000_000L
    }

    def 'empty file retains all columns and types'() {
        given:
        def schema = table.schema
        Fixtures.write(file, true)

        expect:
        table.rowCount == 0L
        table.rows == []
        table.schema == schema
        table.columnNames.size() == 8
    }

    def 'multiple row groups retain their distinct compression codecs'() {
        given:
        def parts = [CompressionCodecName.SNAPPY, CompressionCodecName.UNCOMPRESSED].collect { codec ->
            def part = directory.resolve("${codec}.parquet")
            try (def writer = CarpetParquetWriter.builder(new LocalOutputFile(part), Fixtures.DifferentialExpression)
                    .withCompressionCodec(codec).build()) {
                Fixtures.ROWS.each { writer.write(it) }
            }
            part
        }
        def schema
        try (def reader = ParquetFileReader.open(new LocalInputFile(file.toPath()))) {
            schema = reader.footer.fileMetaData.schema
        }
        file.delete()
        try (def writer = new ParquetFileWriter(new LocalOutputFile(file.toPath()), schema,
                ParquetFileWriter.Mode.CREATE, 1024L, 0)) {
            writer.start()
            parts.each { writer.appendFile(new LocalInputFile(it)) }
            writer.end([:])
        }

        expect:
        table.rowCount == 6L
        table.rowGroupCount == 2
        table.compressionCodecs == ['SNAPPY', 'UNCOMPRESSED']
        table.rowGroups*.index == [0, 1]
        table.rowGroups*.rowCount == [3L, 3L]
        table.rowGroups[0].columns*.codec.unique() == ['SNAPPY']
        table.rowGroups[1].columns*.codec.unique() == ['UNCOMPRESSED']
        table.rows.size() == 6
    }

    def 'Java record projection preserves nullable numbers'() {
        expect:
        table.read(Fixtures.Projection)*.gene() == ['GENE1', 'GENE2', 'GENE3']
        table.read(Fixtures.Projection)[1].log2fc() == null
    }

    def 'unsafe projection fails instead of silently coercing values'() {
        when:
        table.read(projection)

        then:
        def error = thrown(IllegalStateException)
        error.message.contains(file.toString())
        error.cause != null
        noOpenFile(file.toPath())

        where:
        projection << [Fixtures.MissingColumn, Fixtures.PrimitiveProjection, Fixtures.NarrowProjection]
    }

    def 'non-record projection is rejected'() {
        when:
        table.read(String)
        then:
        thrown(IllegalArgumentException)
    }

    def 'nested schema preserves required primitive fields and logical lists'() {
        given:
        Fixtures.writeNested(file)
        when:
        def fields = table.schema.fields
        then:
        fields.find { it.name == 'details' }.fields.find { it.name == 'count' }.nullable == false
        fields.find { it.name == 'details' }.fields.find { it.name == 'count' }.physicalType == 'INT32'
        fields.find { it.name == 'labels' }.logicalType == [type: 'LIST']
        table.rows[0].details.count == 4
    }

    def 'metadata supports nested logical annotations without reading data'() {
        given:
        def schema = MessageTypeParser.parseMessageType('''message example {
            optional fixed_len_byte_array(8) amount (DECIMAL(16,2));
            optional int64 time (TIMESTAMP(MICROS,true));
            optional int32 small (INTEGER(16,false));
        }''')
        file.delete()
        try (def writer = new ParquetFileWriter(new LocalOutputFile(file.toPath()), schema,
                ParquetFileWriter.Mode.CREATE, 1024L, 0)) {
            writer.start()
            writer.end([:])
        }

        expect:
        table.schema.fields[0].logicalType == [type: 'DECIMAL', precision: 16, scale: 2]
        table.schema.fields[0].typeLength == 8
        table.schema.fields[1].logicalType == [type: 'TIMESTAMP', unit: 'MICROS', adjustedToUTC: true]
        table.schema.fields[2].logicalType == [type: 'INTEGER', bitWidth: 16, signed: false]
    }

    def 'missing corrupt and truncated files fail with path and cause'() {
        given:
        if (contents == null) file.delete()
        else file.bytes = contents

        when:
        table.rowCount

        then:
        def error = thrown(IllegalStateException)
        error.message.contains(file.toString())
        error.cause != null

        where:
        contents << [null, 'not parquet'.bytes, 'PAR1'.bytes]
    }

    def 'all reads are closed before caller assertions, including failed reads'() {
        expect:
        (1..10).every {
            try {
                assert table.rows.size() == 999
            } catch (AssertionError ignored) { }
            try {
                table.read(Fixtures.MissingColumn)
            } catch (IllegalStateException ignored) { }
            table.schema
            noOpenFile(file.toPath())
        }

        when:
        Fixtures.write(file, true)
        then:
        table.rows.empty
        noOpenFile(file.toPath())
    }

    private static boolean noOpenFile(Path file) {
        Path fd = Path.of('/proc/self/fd')
        if (!Files.isDirectory(fd)) return true
        try (def entries = Files.list(fd)) {
            return entries.noneMatch { entry ->
                try { return Files.readSymbolicLink(entry) == file }
                catch (IOException ignored) { return false }
            }
        }
    }
}
