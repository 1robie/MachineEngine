package fr.robie.machineengine.paper.player;

import fr.robie.machineengine.api.data.DataBinary;
import fr.robie.machineengine.api.data.DataMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code machineengine:files} (D37): one file per player, written atomically, in order per player. */
class FilePlayerStorageTest {
    private static final UUID ALEX = UUID.fromString("ab000000-0000-0000-0000-00000000a1e5");
    private static final Logger QUIET = quiet();

    @TempDir
    Path folder;
    private FilePlayerStorage storage;

    private static Logger quiet() {
        Logger logger = Logger.getLogger("FilePlayerStorageTest");
        logger.setLevel(Level.OFF);
        return logger;
    }

    private FilePlayerStorage storage() {
        this.storage = new FilePlayerStorage(this.folder, QUIET);
        return this.storage;
    }

    @AfterEach
    void close() {
        if (this.storage != null) this.storage.close();
    }

    private static DataMap data(long visits) {
        return DataMap.builder().put("version", 1).put("visits", visits).build();
    }

    @Test
    void aPlayerWithoutAFileHasNoData() {
        assertEquals(DataMap.EMPTY, storage().load(ALEX, null).join());
    }

    @Test
    void savedDataLoadsBack() {
        FilePlayerStorage files = storage();
        files.save(ALEX, null, data(3)).join();
        assertEquals(this.folder.resolve("ab").resolve(ALEX + ".dat"), files.file(ALEX));
        assertTrue(Files.exists(files.file(ALEX)));
        assertEquals(data(3), files.load(ALEX, null).join());
        assertEquals(data(3), new FilePlayerStorage(this.folder, QUIET).load(ALEX, null).join(), "a new storage reads the file");
    }

    @Test
    void aLoadWaitsForTheSavesBeforeIt() {
        FilePlayerStorage files = storage();
        CompletableFuture<Void> first = null;
        for (long visits = 1; visits <= 50; visits++) {
            CompletableFuture<Void> saved = files.save(ALEX, null, data(visits));
            if (first == null) first = saved;
        }
        // Asked right after the saves, without waiting for them.
        assertEquals(data(50), files.load(ALEX, null).join());
        assertTrue(first.isDone());
    }

    @Test
    void aCrashBeforeTheSwapKeepsTheOldFile() throws IOException {
        FilePlayerStorage files = storage();
        files.save(ALEX, null, data(1)).join();
        // A save that died after writing its temporary file.
        Files.write(files.file(ALEX).resolveSibling(ALEX + ".dat.tmp"), new byte[] {1, 2});
        assertEquals(data(1), files.load(ALEX, null).join());
        files.save(ALEX, null, data(2)).join();
        assertEquals(data(2), files.load(ALEX, null).join());
    }

    @Test
    void aDamagedFileIsKeptAsideAndThePlayerStartsEmpty() throws IOException {
        FilePlayerStorage files = storage();
        Files.createDirectories(files.file(ALEX).getParent());
        Files.write(files.file(ALEX), new byte[] {1, 2, 3});
        assertEquals(DataMap.EMPTY, files.load(ALEX, null).join());
        assertFalse(Files.exists(files.file(ALEX)));
        try (Stream<Path> kept = Files.list(files.file(ALEX).getParent())) {
            assertTrue(kept.anyMatch(path -> path.getFileName().toString().startsWith(ALEX + ".dat.damaged-")));
        }
    }

    @Test
    void theFileHoldsTheBinaryForm() throws IOException {
        FilePlayerStorage files = storage();
        files.save(ALEX, null, data(7)).join();
        assertEquals(data(7), DataBinary.read(Files.readAllBytes(files.file(ALEX))));
    }
}
