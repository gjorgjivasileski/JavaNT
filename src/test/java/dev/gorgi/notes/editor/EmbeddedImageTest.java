package dev.gorgi.notes.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmbeddedImageTest {
  @TempDir Path directory;

  @Test
  void embedsValidatedBytesAndDoesNotDependOnFilenameExtension() throws Exception {
    var encoded =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=";
    var file = directory.resolve("image without extension");
    Files.write(file, Base64.getDecoder().decode(encoded));
    var image = EmbeddedImage.read(file);
    Files.delete(file);
    assertEquals("image without extension", image.name());
    assertEquals("data:image/png;base64," + encoded, image.dataUrl());
    assertTrue(image.width() > 0 && image.width() <= 480);
    assertEquals(image.width(), image.height(), "Import preserves the square aspect ratio");
  }

  @Test
  void rejectsUnsupportedAndCorruptFiles() throws Exception {
    var file = directory.resolve("pretend.png");
    Files.writeString(file, "<svg xmlns='http://www.w3.org/2000/svg'/>");
    assertThrows(IOException.class, () -> EmbeddedImage.read(file));
    Files.write(file, new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
    assertThrows(IOException.class, () -> EmbeddedImage.read(file));
  }

  @Test
  void rejectsOversizedFilesBeforeDecoding() throws Exception {
    var file = directory.resolve("too-large.png");
    try (var output =
        Files.newByteChannel(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      output.position(EmbeddedImage.MAX_BYTES);
      output.write(ByteBuffer.wrap(new byte[] {0}));
    }
    var error = assertThrows(IOException.class, () -> EmbeddedImage.read(file));
    assertTrue(error.getMessage().contains("16 MB"));
  }
}
