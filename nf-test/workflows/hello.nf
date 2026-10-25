nextflow.enable.dsl = 2

// Fixtures are generated locally by Gradle, not downloaded or written by a plugin.
process COPY_PARQUET {
    input:
    path fixture

    output:
    path "copied/${fixture.name}", emit: parquet_file

    script:
    """
    mkdir -p copied
    cp '${fixture.name}' 'copied/${fixture.name}'
    """
}
