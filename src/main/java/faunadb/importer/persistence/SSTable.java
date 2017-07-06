package faunadb.importer.persistence;

import org.mapdb.*;
import org.mapdb.serializer.GroupSerializer;
import org.mapdb.serializer.SerializerArray;
import org.mapdb.volume.MappedFileVol;
import org.mapdb.volume.RandomAccessFileVol;
import org.mapdb.volume.Volume;
import org.mapdb.volume.VolumeFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

final class SSTable {

  private static final GroupSerializer<String[]> KEYS = new SerializerArray<>(Serializer.STRING_DELTA, String.class);
  private static final GroupSerializer<Long> VALUES = Serializer.LONG;

  private SSTable() {
  }

  static final class Write implements Closeable {
    private final DB db;
    private final File dbFile;
    private final BTreeMap<String[], Long> table;

    @SuppressWarnings("ResultOfMethodCallIgnored")
    Write(File dbFile) {
      this.dbFile = dbFile;

      File dbTempFile = new File(dbFile.getParent(), dbFile.getName() + ".tmp");
      dbTempFile.delete(); // Remove old temporary file if exist

      this.db = DBMaker
        .fileDB(dbTempFile)
        .fileMmapEnableIfSupported()
        .fileMmapPreclearDisable()
        .fileDeleteAfterClose()
        .make();

      this.table = db
        .treeMap("db")
        .keySerializer(KEYS)
        .valueSerializer(VALUES)
        .counterEnable()
        .create();
    }

    Long put(String key, String subKey, Long value) {
      return table.put(new String[]{key, subKey}, value);
    }

    Long size() {
      return table.sizeLong();
    }

    @Override
    public void close() {
      try (
        Volume volume =
          volumeFactory()
            .makeVolume(dbFile.getAbsolutePath(), false)
      ) {
        SortedTableMap<String[], Long> sstable = SortedTableMap
          .create(volume, KEYS, VALUES)
          .createFrom(table);

        sstable.close();
      } finally {
        table.close();
        db.close();
      }
    }
  }

  static final class Read implements Closeable {
    private final Volume volume;
    private final SortedTableMap<String[], Long> sstable;

    Read(File dbFile) {
      this.volume = volumeFactory().makeVolume(dbFile.getAbsolutePath(), true);
      this.sstable = SortedTableMap.open(volume, KEYS, VALUES);
    }

    Long get(String key, String subKey) {
      return sstable.get(new String[]{key, subKey});
    }

    @Override
    public void close() throws IOException {
      sstable.close();
      volume.close();
    }
  }

  private static VolumeFactory volumeFactory() {
    if (jvmSupportsLargeMappedFiles()) return MappedFileVol.FACTORY;
    return RandomAccessFileVol.FACTORY;
  }

  private static boolean jvmSupportsLargeMappedFiles() {
    String arch = System.getProperty("os.arch", "");
    String os = System.getProperty("os.name", "").toLowerCase();
    return arch.contains("64") && !os.startsWith("windows");
  }
}
