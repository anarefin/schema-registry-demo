package com.example.schemagen;

import com.example.schemagen.mappingfixtures.GoodOrderCreated;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaGeneratorCliDualWriteTest {

    @Test
    void writeEventSchema_writesIdenticalContentToCommittedAndClasspathDirs(@TempDir Path committed,
                                                                            @TempDir Path classes)
            throws Exception {
        SchemaGeneratorCli.writeEventSchema(GoodOrderCreated.class, committed, classes);

        String fileName = SchemaFileNaming.toFileName(GoodOrderCreated.class.getSimpleName());
        Path committedPath = committed.resolve(fileName);
        Path classpathPath = classes.resolve("schemas").resolve(fileName);

        assertThat(committedPath).exists();
        assertThat(classpathPath).exists();
        assertThat(Files.readString(classpathPath)).isEqualTo(Files.readString(committedPath));
        assertThat(Files.readString(committedPath)).contains("\"$schema\"");
    }
}
