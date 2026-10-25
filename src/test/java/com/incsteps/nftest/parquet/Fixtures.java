package com.incsteps.nftest.parquet;

import com.jerolba.carpet.CarpetWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/** Small deterministic fixtures shared by unit and nf-test integration tests. */
public final class Fixtures {
    public record DifferentialExpression(String gene, Double log2fc, Double pvalue,
            Double padj, String group, String contrast, String stratum, Boolean significant) {}
    public record Projection(String gene, Double log2fc) {}
    public record MissingColumn(String nonexistent) {}
    public record PrimitiveProjection(double log2fc) {}
    public record NarrowProjection(float log2fc) {}
    public record Nested(String gene, Details details, List<String> labels) {}
    public record Details(int count, String description) {}

    public static final List<DifferentialExpression> ROWS = List.of(
            new DifferentialExpression("GENE1", 1.25, 0.01, 0.02, "treated", "treated-control", "all", true),
            new DifferentialExpression("GENE2", null, Double.NaN, Double.POSITIVE_INFINITY,
                    "control", "treated-control", null, false),
            new DifferentialExpression("GENE3", Double.NEGATIVE_INFINITY, 0.0, null,
                    "treated", "treated-control", "α β", null));

    public static void write(File file, boolean empty) throws IOException {
        try (var writer = new CarpetWriter<>(new FileOutputStream(file), DifferentialExpression.class)) {
            if (!empty) writer.write(ROWS);
        }
    }

    public static void writeNested(File file) throws IOException {
        try (var writer = new CarpetWriter<>(new FileOutputStream(file), Nested.class)) {
            writer.write(new Nested("GENE1", new Details(4, null), List.of("a", "b")));
        }
    }

    public static void main(String[] args) throws IOException {
        File directory = new File(args[0]);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create fixture directory " + directory);
        }
        write(new File(directory, "DE results:case.parquet"), false);
        write(new File(directory, "empty.parquet"), true);
    }
}
