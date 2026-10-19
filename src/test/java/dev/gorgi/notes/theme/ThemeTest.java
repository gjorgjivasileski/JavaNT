package dev.gorgi.notes.theme;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ThemeTest {
    @Test
    void readsOmarchyLightAndDarkPalettesWithoutHardCodingLightColors() {
        for (String background : new String[]{"#fafafa", "#1a1b26"}) {
            Theme theme = Theme.parse("""
                    mode = "dark"
                    background = '%s'
                    dark_background = "#16161e"
                    darker_background = "#292e42"
                    foreground = "#c0caf5"
                    dark_foreground = "#565f89"
                    accent = "#7aa2f7"
                    selection = "#283457"
                    """.formatted(background), "JetBrainsMono Nerd Font");

            assertAll(
                    () -> assertEquals(background, theme.background()),
                    () -> assertEquals("#16161e", theme.panel()),
                    () -> assertEquals("#292e42", theme.border()),
                    () -> assertEquals("#c0caf5", theme.foreground()),
                    () -> assertEquals("#565f89", theme.muted()),
                    () -> assertEquals("#7aa2f7", theme.accent()),
                    () -> assertEquals("#283457", theme.selection()),
                    () -> assertTrue(theme.fxStyle().contains(background)),
                    () -> assertTrue(theme.webCss().contains(background)));
        }
    }

    @Test
    void partialPaletteUsesTheAvailableColorsAndSafeFallbacks() {
        Theme theme = Theme.parse("""
                background = "#111111"
                foreground = "#eeeeee"
                selection = "#333333"
                muted = "#999999"
                """, "Iosevka");

        assertAll(
                () -> assertEquals("#111111", theme.panel()),
                () -> assertEquals("#333333", theme.border()),
                () -> assertEquals("#999999", theme.muted()),
                () -> assertEquals(Theme.fallback().accent(), theme.accent()),
                () -> assertEquals("Iosevka", theme.font()));
    }

    @Test
    void ignoresCommentsInvalidColorsAndUnrelatedSettings() {
        Theme theme = Theme.parse("""
                # background = "#ff0000"
                background = "not a color"
                accent = "#12345g"
                unrelated = "#abcdef"
                selection = "#112233" # an inline comment
                """, "Monospace");

        assertAll(
                () -> assertEquals(Theme.fallback().background(), theme.background()),
                () -> assertEquals(Theme.fallback().accent(), theme.accent()),
                () -> assertEquals("#112233", theme.selection()));
    }

    @Test
    void fontNamesCannotBreakOutOfStyleDeclarationsOrTheHtmlStyleElement() {
        Theme theme = Theme.parse("", "Mono';color:red;} </style><script>alert(1)</script>");

        assertAll(
                () -> assertFalse(theme.font().contains("'")),
                () -> assertFalse(theme.font().contains(";")),
                () -> assertFalse(theme.font().contains("<")),
                () -> assertFalse(theme.font().contains(">")),
                () -> assertFalse(theme.webCss().contains("</style>")),
                () -> assertFalse(theme.fxStyle().contains("color:red")));
    }

    @Test
    void noteAndTaskColorsFollowTheThemePalette() {
        Theme first = Theme.parse("""
                background = "#1a1b26"
                dark_background = "#16161e"
                accent = "#7aa2f7"
                red = "#f7768e"
                """, "Monospace");
        Theme second = Theme.parse("""
                background = "#282828"
                dark_background = "#1d2021"
                accent = "#b8bb26"
                red = "#fb4934"
                """, "Monospace");

        assertAll(
                () -> assertEquals(first.accent(), first.noteColor(), "A readable theme accent should be preserved"),
                () -> assertEquals(second.accent(), second.noteColor(), "A readable theme accent should be preserved"),
                () -> assertNotEquals(first.noteColor(), second.noteColor()),
                () -> assertNotEquals(first.taskColor(), second.taskColor()));
        assertReadableDistinctEntityColors(first);
        assertReadableDistinctEntityColors(second);
    }

    @Test
    void aSecondaryPaletteChangeTriggersThemeReloadWithoutChangingTheAccent() {
        String base = """
                background = "#16161e"
                foreground = "#c0caf5"
                accent = "#7aa2f7"
                """;
        Theme first = Theme.parse(base + "red = '#f7768e'", "Monospace");
        Theme second = Theme.parse(base + "red = '#e0af68'", "Monospace");

        assertAll(
                () -> assertEquals(first.background(), second.background()),
                () -> assertEquals(first.accent(), second.accent()),
                () -> assertEquals(first.noteColor(), second.noteColor()),
                () -> assertNotEquals(first.taskColor(), second.taskColor()),
                () -> assertNotEquals(first, second, "Theme polling must observe secondary palette changes"),
                () -> assertEquals(first, Theme.parse(base + "red = '#f7768e'", "Monospace")));
    }

    @Test
    void lightAndDarkThemesKeepBothEntityColorsReadable() {
        for (String[] surfaces : new String[][]{
                {"#fafafa", "#f1f1f1"},
                {"#16161e", "#20222b"}
        }) {
            Theme theme = Theme.parse("""
                    background = '%s'
                    dark_background = '%s'
                    accent = "#808080"
                    green = "#9ece6a"
                    magenta = "#bb9af7"
                    """.formatted(surfaces[0], surfaces[1]), "Monospace");

            assertReadableDistinctEntityColors(theme);
        }
    }

    @Test
    void duplicateAndMonochromePalettesStillProduceDifferentReadableEntityColors() {
        for (String color : new String[]{"#7aa2f7", "#888888"}) {
            for (String background : new String[]{"#fafafa", "#16161e"}) {
                Theme theme = Theme.parse("""
                        background = '%s'
                        accent = '%s'
                        red = '%s'
                        green = '%s'
                        blue = '%s'
                        magenta = '%s'
                        cyan = '%s'
                        yellow = '%s'
                        """.formatted(background, color, color, color, color, color, color, color), "Monospace");

                assertReadableDistinctEntityColors(theme);
            }
        }
    }

    @Test
    void missingOrInvalidPaletteColorsHaveReadableDistinctFallbacks() {
        for (String palette : new String[]{
                "",
                "background = '#fafafa'\naccent = '#e5e5e5'",
                "background = '#16161e'\naccent = '#16161e'",
                "background = '#fafafa'\naccent = 'invalid'\nred = '#12345g'\ngreen = '#abc'",
                "background = '#16161e'\naccent = '#7aa2f7'\nred = '#7aa2f8'"
        }) {
            assertReadableDistinctEntityColors(Theme.parse(palette, "Monospace"));
        }
        assertReadableDistinctEntityColors(Theme.fallback());
    }

    @Test
    void opposingSurfacesStillProduceDifferentFinalHexColors() {
        for (String[] surfaces : new String[][]{
                {"#ffffff", "#000000"},
                {"#111111", "#757575"},
                {"#757575", "#111111"}
        }) {
            for (String accent : new String[]{"#000000", "#ffffff", "#888888", "#7aa2f7"}) {
                Theme theme = Theme.parse("""
                        background = '%s'
                        dark_background = '%s'
                        accent = '%s'
                        red = '%s'
                        """.formatted(surfaces[0], surfaces[1], accent, accent), "Monospace");

                assertAll(
                        () -> assertTrue(theme.noteColor().matches("#[0-9a-fA-F]{6}")),
                        () -> assertTrue(theme.taskColor().matches("#[0-9a-fA-F]{6}")),
                        () -> assertNotEquals(theme.noteColor().toLowerCase(java.util.Locale.ROOT),
                                theme.taskColor().toLowerCase(java.util.Locale.ROOT),
                                "Contrast adjustment must not collapse notes and tasks to the same final color"));
            }
        }
    }

    @Test
    void nativeLabelsAndMarkdownCheckboxesShareTheEntityPalette() {
        for (String background : new String[]{"#fafafa", "#16161e"}) {
            Theme theme = Theme.parse("""
                    background = '%s'
                    accent = "#7aa2f7"
                    red = "#f7768e"
                    """.formatted(background), "Monospace");

            assertAll(
                    () -> assertTrue(theme.fxStyle().contains("-note-color:" + theme.noteColor() + ";")),
                    () -> assertTrue(theme.fxStyle().contains("-task-color:" + theme.taskColor() + ";")),
                    () -> assertTrue(theme.webCss().contains("input[type=checkbox]{accent-color:" + theme.taskColor() + ";")));
        }
    }

    private static void assertReadableDistinctEntityColors(Theme theme) {
        assertNotEquals(theme.noteColor().toLowerCase(java.util.Locale.ROOT),
                theme.taskColor().toLowerCase(java.util.Locale.ROOT), "Notes and tasks must use different colors");
        for (String color : new String[]{theme.noteColor(), theme.taskColor()}) {
            assertTrue(color.matches("#[0-9a-fA-F]{6}"), "Entity color must be a valid CSS hex color: " + color);
            for (String surface : new String[]{theme.background(), theme.panel()}) {
                double contrast = contrastRatio(color, surface);
                assertTrue(contrast >= 4.5, () -> color + " on " + surface + " has insufficient text contrast: " + contrast);
            }
        }
        assertTrue(rgbDistance(theme.noteColor(), theme.taskColor()) >= 0.25,
                "Notes and tasks must remain visibly different, including with near-duplicate palette colors");
    }

    private static double rgbDistance(String first, String second) {
        double squaredDistance = 0;
        for (int channel = 0; channel < 3; channel++) {
            int start = 1 + channel * 2;
            double difference = (Integer.parseInt(first.substring(start, start + 2), 16)
                    - Integer.parseInt(second.substring(start, start + 2), 16)) / 255.0;
            squaredDistance += difference * difference;
        }
        return Math.sqrt(squaredDistance);
    }

    private static double contrastRatio(String first, String second) {
        double firstLuminance = luminance(first), secondLuminance = luminance(second);
        return (Math.max(firstLuminance, secondLuminance) + 0.05)
                / (Math.min(firstLuminance, secondLuminance) + 0.05);
    }

    private static double luminance(String color) {
        double[] linear = new double[3];
        for (int channel = 0; channel < linear.length; channel++) {
            double srgb = Integer.parseInt(color.substring(1 + channel * 2, 3 + channel * 2), 16) / 255.0;
            linear[channel] = srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    }
}
