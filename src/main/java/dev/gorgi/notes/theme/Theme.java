package dev.gorgi.notes.theme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javafx.scene.paint.Color;

public record Theme(
    String background,
    String panel,
    String border,
    String foreground,
    String muted,
    String accent,
    String selection,
    String font,
    String noteColor,
    String taskColor) {
  private static final double MIN_TEXT_CONTRAST = 4.5;
  private static final List<String> PALETTE_COLORS =
      List.of(
          "red",
          "yellow",
          "orange",
          "green",
          "cyan",
          "blue",
          "magenta",
          "brown",
          "bright_red",
          "bright_yellow",
          "bright_orange",
          "bright_green",
          "bright_cyan",
          "bright_blue",
          "bright_magenta",
          "bright_brown");

  public Theme(
      String background,
      String panel,
      String border,
      String foreground,
      String muted,
      String accent,
      String selection,
      String font) {
    this(
        background,
        panel,
        border,
        foreground,
        muted,
        accent,
        selection,
        font,
        entityColors(background, panel, accent, Map.of()));
  }

  private Theme(
      String background,
      String panel,
      String border,
      String foreground,
      String muted,
      String accent,
      String selection,
      String font,
      EntityColors entities) {
    this(
        background,
        panel,
        border,
        foreground,
        muted,
        accent,
        selection,
        font,
        entities.note(),
        entities.task());
  }

  public static Theme fallback() {
    return new Theme(
        "#fafafa",
        "#f1f1f1",
        "#dedede",
        "#212121",
        "#757575",
        "#3264eb",
        "#d0d0d0",
        "JetBrainsMono Nerd Font");
  }

  public static Theme load() {
    var home = Path.of(System.getProperty("user.home"));
    var state = envPath("XDG_STATE_HOME", home.resolve(".local/state"));
    var config = envPath("XDG_CONFIG_HOME", home.resolve(".config"));
    for (var path :
        List.of(
            state.resolve("omarchy/current/theme/colors.toml"),
            config.resolve("omarchy/current/theme/colors.toml"))) {
      try {
        return parse(Files.readString(path), readFont(config)).resolveFont();
      } catch (IOException ignored) {
        /* Omarchy is optional; use the next location or built-in palette. */
      }
    }
    return fallback().resolveFont();
  }

  private Theme resolveFont() {
    // Fontconfig and JavaFX expose different family names for some Nerd Fonts.
    var families = javafx.scene.text.Font.getFamilies();
    String alias = font.replace(" Nerd Font Mono", " NFM").replace(" Nerd Font", " NF");
    String resolved =
        families.contains(font) ? font : families.contains(alias) ? alias : "Monospaced";
    return new Theme(
        background,
        panel,
        border,
        foreground,
        muted,
        accent,
        selection,
        resolved,
        noteColor,
        taskColor);
  }

  private static Path envPath(String key, Path fallback) {
    var value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : Path.of(value);
  }

  private static String readFont(Path config) {
    try {
      var match =
          Pattern.compile("(?m)^\\s*font\\s*=\\s*([^:\\r\\n]+)")
              .matcher(Files.readString(config.resolve("foot/foot.ini")));
      if (match.find()) {
        return match.group(1).strip();
      }
    } catch (IOException ignored) {
    }
    try {
      var match =
          Pattern.compile("family\\s*=\\s*\"([^\"]+)\"")
              .matcher(Files.readString(config.resolve("alacritty/alacritty.toml")));
      if (match.find()) {
        return match.group(1);
      }
    } catch (IOException ignored) {
    }
    return fallback().font();
  }

  public static Theme parse(String toml, String font) {
    var colors = new HashMap<String, String>();
    var matcher =
        Pattern.compile("(?m)^\\s*([a-z_]+)\\s*=\\s*[\"'](#[0-9a-fA-F]{6})[\"']").matcher(toml);
    while (matcher.find()) {
      colors.put(matcher.group(1), matcher.group(2));
    }
    var f = fallback();
    var bg = colors.getOrDefault("background", f.background);
    var panel = colors.getOrDefault("dark_background", bg);
    var fg = colors.getOrDefault("foreground", f.foreground);
    var accent = colors.getOrDefault("accent", f.accent);
    return new Theme(
        bg,
        panel,
        colors.getOrDefault("darker_background", colors.getOrDefault("selection", f.border)),
        fg,
        colors.getOrDefault("dark_foreground", colors.getOrDefault("muted", fg)),
        accent,
        colors.getOrDefault("selection", f.selection),
        font.replaceAll("[^\\p{L}\\p{N} _-]", ""),
        entityColors(bg, panel, accent, colors));
  }

  private record EntityColors(String note, String task) {}

  private static EntityColors entityColors(
      String background, String panel, String accent, Map<String, String> palette) {
    var bg = Color.web(background);
    var surface = Color.web(panel);
    var note = readable(Color.web(accent), bg, surface);
    Color task = null;
    double bestDistance = -1;
    for (var key : PALETTE_COLORS) {
      var value = palette.get(key);
      if (value == null) {
        continue;
      }
      var candidate = readable(Color.web(value), bg, surface);
      double distance = colorDistance(note, candidate);
      if (distinct(note, candidate) && distance > bestDistance) {
        task = candidate;
        bestDistance = distance;
      }
    }
    if (task == null) {
      // Sparse and monochrome themes still get a second, theme-derived color.
      // Try the opposite hue first, then other hues if contrast adjustments make it too similar.
      double hue = note.getSaturation() >= .12 ? note.getHue() : bg.getHue();
      // A conflicting pair of light/dark surfaces can leave no distinct 4.5:1 colors.
      // In that case prioritize separation while retaining at least 3:1 where possible.
      for (double minimumContrast : new double[] {MIN_TEXT_CONTRAST, 3}) {
        for (int offset : new int[] {180, 120, 240, 60, 300}) {
          var candidate =
              readable(
                  Color.hsb((hue + offset) % 360, Math.max(.65, note.getSaturation()), .8),
                  bg,
                  surface,
                  minimumContrast);
          double distance = colorDistance(note, candidate);
          if (distinct(note, candidate)) {
            task = candidate;
            break;
          }
          if (task == null || distance > bestDistance) {
            task = candidate;
            bestDistance = distance;
          }
        }
        if (distinct(note, task)) {
          break;
        }
      }
    }
    // Keep the user-visible identities separate even for degenerate custom palettes.
    if (hex(note).equals(hex(task))) {
      task = note.interpolate(luminance(note) < .5 ? Color.WHITE : Color.BLACK, .1);
    }
    return new EntityColors(hex(note), hex(task));
  }

  private static boolean distinct(Color note, Color task) {
    double hueDifference = Math.abs(note.getHue() - task.getHue());
    hueDifference = Math.min(hueDifference, 360 - hueDifference);
    return task.getSaturation() >= .15
        && colorDistance(note, task) >= .28
        && (note.getSaturation() < .12 || hueDifference >= 35);
  }

  private static Color readable(Color color, Color background, Color panel) {
    return readable(color, background, panel, MIN_TEXT_CONTRAST);
  }

  private static Color readable(
      Color color, Color background, Color panel, double minimumContrast) {
    if (surfaceContrast(color, background, panel) >= minimumContrast) {
      return color;
    }
    var best = color;
    double bestContrast = surfaceContrast(color, background, panel);
    // Preserve the palette hue, changing only as much lightness as readability needs.
    // Opposing light/dark surfaces may not permit 4.5:1; retain the best shared contrast then.
    for (int step = 1; step <= 100; step++) {
      for (var endpoint : List.of(Color.BLACK, Color.WHITE)) {
        var candidate = Color.web(hex(color.interpolate(endpoint, step / 100.0)));
        double contrast = surfaceContrast(candidate, background, panel);
        if (contrast >= minimumContrast) {
          return candidate;
        }
        if (contrast > bestContrast) {
          best = candidate;
          bestContrast = contrast;
        }
      }
    }
    return best;
  }

  private static double surfaceContrast(Color color, Color background, Color panel) {
    return Math.min(contrast(color, background), contrast(color, panel));
  }

  private static double contrast(Color first, Color second) {
    double a = luminance(first);
    double b = luminance(second);
    return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
  }

  private static double luminance(Color color) {
    return .2126 * linear(color.getRed())
        + .7152 * linear(color.getGreen())
        + .0722 * linear(color.getBlue());
  }

  private static double linear(double channel) {
    return channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
  }

  private static double colorDistance(Color first, Color second) {
    double red = first.getRed() - second.getRed();
    double green = first.getGreen() - second.getGreen();
    double blue = first.getBlue() - second.getBlue();
    return Math.sqrt(red * red + green * green + blue * blue);
  }

  private static String hex(Color color) {
    return "#%02x%02x%02x"
        .formatted(
            Math.round(color.getRed() * 255),
            Math.round(color.getGreen() * 255),
            Math.round(color.getBlue() * 255));
  }

  public String fxStyle() {
    return "-app-bg:"
        + background
        + ";-app-panel:"
        + panel
        + ";-app-border:"
        + border
        + ";-app-fg:"
        + foreground
        + ";-app-muted:"
        + muted
        + ";-app-accent:"
        + accent
        + ";-app-selection:"
        + selection
        + ";-note-color:"
        + noteColor
        + ";-task-color:"
        + taskColor
        + ";-fx-font-family:'"
        + font
        + "';";
  }

  public String webCss() {
    // WebView does not implement accent-color; draw a local checkmark using the same task palette.
    var checkmark =
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'><path d='M3 8 6 11 13 4'"
            + " fill='none' stroke='"
            + background
            + "' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'/></svg>";
    var checkmarkImage =
        Base64.getEncoder()
            .encodeToString(checkmark.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return "html{color-scheme:light dark}body{background:"
        + background
        + ";color:"
        + foreground
        + ";font-family:'"
        + font
        + "',monospace;font-size:14px;line-height:1.8;width:100%;margin:0;padding:8px;overflow-wrap:anywhere;min-height:80vh;box-sizing:border-box;outline:none}h1,h2,h3{line-height:1.35;letter-spacing:-.04em}h1{font-size:27px;margin:0"
        + " 0 24px}h2{font-size:20px;margin-top:32px}a{color:"
        + accent
        + "}code,pre{font-family:inherit;background:"
        + panel
        + ";border-radius:3px}code{padding:2px 5px}pre{padding:16px;overflow:auto}pre"
        + " code{padding:0}blockquote{margin:24px 0;padding:4px 18px;border-left:3px solid "
        + accent
        + ";color:"
        + muted
        + "}"
        + "table{border-collapse:collapse;width:100%;table-layout:fixed}td,th{border:1px solid "
        + border
        + ";padding:8px 12px;text-align:left;overflow-wrap:anywhere}"
        + "hr{border:0;border-top:1px solid "
        + border
        + ";margin:28px 0}::selection{background:"
        + selection
        + "}input[type=checkbox]{accent-color:"
        + taskColor
        + ";appearance:none;-webkit-appearance:none;box-sizing:border-box;border:1px solid "
        + taskColor
        + ";border-radius:2px;background-color:"
        + background
        + ";cursor:pointer;width:16px;height:16px;vertical-align:middle}"
        + "input[type=checkbox]:checked{background-color:"
        + taskColor
        + ";background-image:url('data:image/svg+xml;base64,"
        + checkmarkImage
        + "');background-size:contain;background-position:center;background-repeat:no-repeat}"
        + "input[type=checkbox]:focus-visible{outline:2px solid "
        + taskColor
        + ";outline-offset:2px}"
        + "li.task-list-item{list-style:none}ul.task-list,ol.task-list{padding-left:0}"
        + "li.task-list-item>p:first-of-type{display:inline}"
        + "li ul.task-list,li ol.task-list{padding-left:1.5em}"
        + imageCss();
  }

  private static String imageCss() {
    return """
    body{position:relative;isolation:isolate}
    img.note-image{max-width:none;max-height:none;margin:0;vertical-align:middle;
      cursor:pointer;user-select:none;-webkit-user-select:none;-webkit-user-drag:none}
    img.note-image[data-image-mode=floating]{cursor:move}
    .note-image-selection{position:absolute;box-sizing:border-box;pointer-events:none;
      border:2px solid currentColor;color:inherit;z-index:2147483646;line-height:1;
      user-select:none;-webkit-user-select:none}
    .note-image-selection [data-image-handle]{position:absolute;box-sizing:border-box;
      pointer-events:auto;background:currentColor;border:2px solid white;
      box-shadow:0 0 0 1px currentColor;border-radius:2px;padding:0;min-width:0}
    .note-image-selection [data-image-handle=resize]{width:14px;height:14px;
      right:-7px;bottom:-7px;cursor:nwse-resize}
    .note-image-selection [data-image-handle=move]{width:26px;height:12px;
      left:calc(50% - 13px);top:-7px;cursor:move}
    """;
  }
}
