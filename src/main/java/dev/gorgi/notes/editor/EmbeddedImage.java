package dev.gorgi.notes.editor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javafx.scene.image.Image;

/** An imported image whose bytes travel with the Markdown instead of referring to a local file. */
public record EmbeddedImage(String name, String dataUrl, int width, int height) {
  static final int MAX_BYTES = 16 * 1024 * 1024;

  /** Reads and validates a PNG, JPEG, or GIF off the application thread. */
  public static EmbeddedImage read(Path path) throws IOException {
    byte[] bytes;
    try (var input = Files.newInputStream(path)) {
      bytes = input.readNBytes(MAX_BYTES + 1);
    }
    if (bytes.length > MAX_BYTES) {
      throw new IOException("Choose an image of 16 MB or less.");
    }
    String type = imageType(bytes);
    // Decode a bounded thumbnail to validate the file and get its aspect ratio.
    var image = new Image(new ByteArrayInputStream(bytes), 480, 480, true, true);
    if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
      throw new IOException(
          "This image could not be read. Choose a valid PNG, JPEG, or GIF.", image.getException());
    }
    return new EmbeddedImage(
        path.getFileName().toString(),
        "data:image/" + type + ";base64," + Base64.getEncoder().encodeToString(bytes),
        Math.max(1, (int) Math.round(image.getWidth())),
        Math.max(1, (int) Math.round(image.getHeight())));
  }

  private static String imageType(byte[] bytes) throws IOException {
    if (bytes.length >= 8
        && bytes[0] == (byte) 0x89
        && bytes[1] == 'P'
        && bytes[2] == 'N'
        && bytes[3] == 'G'
        && bytes[4] == 13
        && bytes[5] == 10
        && bytes[6] == 26
        && bytes[7] == 10) {
      return "png";
    }
    if (bytes.length >= 3
        && bytes[0] == (byte) 0xff
        && bytes[1] == (byte) 0xd8
        && bytes[2] == (byte) 0xff) {
      return "jpeg";
    }
    if (bytes.length >= 6
        && bytes[0] == 'G'
        && bytes[1] == 'I'
        && bytes[2] == 'F'
        && bytes[3] == '8'
        && (bytes[4] == '7' || bytes[4] == '9')
        && bytes[5] == 'a') {
      return "gif";
    }
    throw new IOException("Choose a PNG, JPEG, or GIF image.");
  }
}
