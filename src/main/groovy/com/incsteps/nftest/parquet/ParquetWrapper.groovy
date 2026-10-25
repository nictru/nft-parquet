/*
 * Copyright 2025, Incremental Steps Software Solution
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.incsteps.nftest.parquet

import groovy.transform.CompileStatic
import com.jerolba.carpet.CarpetParquetReader
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.metadata.ParquetMetadata
import org.apache.parquet.io.LocalInputFile
import org.apache.parquet.schema.Type
import org.apache.parquet.schema.LogicalTypeAnnotation
import org.apache.parquet.schema.LogicalTypeAnnotation.*

/**
 *
 * @author Jorge Aguilera <jorge@incsteps.com>
 */
@CompileStatic
class ParquetWrapper {

    private final File parquetFile
    ParquetWrapper(File parquetFile, Map arguments) {
        if (parquetFile == null) {
            throw new IllegalArgumentException('A local Parquet file is required')
        }
        if (arguments == null || !arguments.isEmpty()) {
            throw new IllegalArgumentException('Parquet inspection does not accept options; use read(RecordClass) for projection')
        }
        this.parquetFile = parquetFile.absoluteFile
    }

    long getRowCount() {
        return rowCount()
    }

    long rowCount() {
        withFooter { ParquetMetadata footer ->
            long count = 0L
            for (def group : footer.blocks) {
                count = Math.addExact(count, group.rowCount)
            }
            count
        }
    }

    List<String> getColumnNames() {
        withFooter { ParquetMetadata footer -> footer.fileMetaData.schema.fields.collect { Type field -> field.name } }
    }

    Map<String, Object> getSchema() {
        withFooter { ParquetMetadata footer ->
            [name: footer.fileMetaData.schema.name,
             fields: footer.fileMetaData.schema.fields.collect { Type field -> describeField(field) }] as Map<String, Object>
        }
    }

    int getRowGroupCount() {
        withFooter { ParquetMetadata footer -> footer.blocks.size() }
    }

    List<String> getCompressionCodecs() {
        withFooter { ParquetMetadata footer ->
            Set<String> codecs = new TreeSet<>()
            for (def group : footer.blocks) {
                for (def column : group.columns) {
                    codecs.add(column.codec.name())
                }
            }
            new ArrayList<String>(codecs)
        }
    }

    List<Map<String, Object>> getRowGroups() {
        withFooter { ParquetMetadata footer ->
            List<Map<String, Object>> groups = []
            footer.blocks.eachWithIndex { group, int index ->
                groups.add([
                    index: index, rowCount: group.rowCount, totalByteSize: group.totalByteSize,
                    columns: group.columns.collect { column ->
                        [path: column.path.toArray().toList(), codec: column.codec.name(),
                         valueCount: column.valueCount, compressedSize: column.totalSize,
                         uncompressedSize: column.totalUncompressedSize]
                    }
                ] as Map<String, Object>)
            }
            groups
        }
    }

    List<Map<String, Object>> getRows() {
        (List<Map<String, Object>>) (List) readRows(Map)
    }

    public <T> List<T> read(Class<T> recordClass) {
        if (recordClass == null || !recordClass.isRecord()) {
            throw new IllegalArgumentException('read requires a Java record class')
        }
        readRows(recordClass)
    }

    private <T> List<T> readRows(Class<T> rowClass) {
        // Own the reader before the first read: CarpetReader.stream() prefetches
        // during construction, before a caller can install its cleanup handler.
        try (ParquetReader<T> reader = CarpetParquetReader.builder(new LocalInputFile(parquetFile.toPath()), rowClass)
                .failOnNullForPrimitives(true)
                .failNarrowingPrimitiveConversion(true)
                .build()) {
            List<T> rows = []
            T row
            while ((row = reader.read()) != null) {
                rows.add(row)
            }
            return rows
        }
        catch (Exception error) {
            throw inspectionError(error)
        }
    }

    private <T> T withFooter(Closure<T> inspect) {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(parquetFile.toPath()))) {
            return inspect.call(reader.footer)
        }
        catch (Exception error) {
            throw inspectionError(error)
        }
    }

    private IllegalStateException inspectionError(Exception cause) {
        new IllegalStateException("Cannot inspect Parquet file '${parquetFile}': ${cause.message}", cause)
    }

    private static Map<String, Object> describeField(Type field) {
        Map<String, Object> result = [
            name: field.name,
            repetition: field.repetition.name(),
            nullable: field.repetition == Type.Repetition.OPTIONAL,
            physicalType: field.primitive ? field.asPrimitiveType().primitiveTypeName.name() : null,
            logicalType: describeLogicalType(field.logicalTypeAnnotation),
            fields: field.primitive ? [] : field.asGroupType().fields.collect { Type child -> describeField(child) }
        ] as Map<String, Object>
        if (field.primitive && field.asPrimitiveType().primitiveTypeName.name() == 'FIXED_LEN_BYTE_ARRAY') {
            result.put('typeLength', field.asPrimitiveType().typeLength)
        }
        result
    }

    private static Map<String, Object> describeLogicalType(LogicalTypeAnnotation logical) {
        if (logical == null) return null
        // The annotation's stable token is the portion before its optional parameters.
        Map<String, Object> result = [type: logical.toString().split('\\(')[0]] as Map<String, Object>
        if (logical instanceof DecimalLogicalTypeAnnotation) {
            result.put('precision', logical.precision)
            result.put('scale', logical.scale)
        }
        else if (logical instanceof TimestampLogicalTypeAnnotation) {
            result.put('unit', logical.unit.name())
            result.put('adjustedToUTC', logical.isAdjustedToUTC())
        }
        else if (logical instanceof TimeLogicalTypeAnnotation) {
            result.put('unit', logical.unit.name())
            result.put('adjustedToUTC', logical.isAdjustedToUTC())
        }
        else if (logical instanceof IntLogicalTypeAnnotation) {
            result.put('bitWidth', logical.bitWidth)
            result.put('signed', logical.isSigned())
        }
        result
    }
}
